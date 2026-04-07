package com.ocrsage.service

import com.ocrsage.dto.DashboardStats
import com.ocrsage.dto.ExtractedInvoiceData
import com.ocrsage.dto.InvoiceResponse
import com.ocrsage.dto.InvoiceUpdateRequest
import com.ocrsage.entity.Invoice
import com.ocrsage.entity.InvoiceLineItem
import com.ocrsage.entity.InvoiceStatus
import com.ocrsage.repository.InvoiceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import jakarta.annotation.PostConstruct
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

@Service
class InvoiceService(
    private val invoiceRepository: InvoiceRepository,
    private val ocrService: OcrService,
    private val regexExtractionService: RegexExtractionService,
    private val tableExtractionService: TableExtractionService,
    private val aiExtractionService: AiExtractionService,
    private val erpConnectorFactory: ErpConnectorFactory,
    private val appSettingsService: AppSettingsService,
    @Value("\${storage.upload-dir}") private val uploadDir: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun init() {
        val uploadPath = Path.of(uploadDir)
        try {
            Files.createDirectories(uploadPath)
            log.info("Upload directory ready: {}", uploadPath.toAbsolutePath())
        } catch (e: Exception) {
            log.error("Cannot create upload directory '{}': {}. Check file system permissions.", uploadPath.toAbsolutePath(), e.message)
            throw IllegalStateException("Upload directory '${uploadPath.toAbsolutePath()}' is not writable. " +
                "Ensure the directory exists and the application has write permissions.", e)
        }
    }

    @Transactional
    fun uploadAndProcess(file: MultipartFile): InvoiceResponse {
        val uploadPath = Path.of(uploadDir)
        Files.createDirectories(uploadPath)

        val fileName = "${System.currentTimeMillis()}_${file.originalFilename}"
        val filePath = uploadPath.resolve(fileName)
        file.transferTo(filePath)

        val invoice = Invoice(fileName = file.originalFilename ?: "unknown", filePath = filePath.toString())
        invoiceRepository.save(invoice)
        log.info("Invoice uploaded: {} (id={})", invoice.fileName, invoice.id)

        // Step 1: OCR text extraction (Tika + Tesseract avec preprocessing)
        try {
            invoice.status = InvoiceStatus.OCR_IN_PROGRESS
            invoiceRepository.save(invoice)

            val ocrResult = ocrService.extractWithDetails(
                Files.newInputStream(filePath), fileName, filePath
            )
            invoice.rawText = ocrResult.text
            invoice.ocrEngine = ocrResult.engine.name
            invoice.ocrConfidence = ocrResult.confidence.takeIf { it >= 0 }
            invoice.ocrPageCount = ocrResult.pageCount
            invoice.status = InvoiceStatus.OCR_COMPLETED
            invoiceRepository.save(invoice)
            log.info("OCR completed for invoice {} (engine={}, {} chars, {} pages)",
                invoice.id, ocrResult.engine, ocrResult.text.length, ocrResult.pageCount)
        } catch (e: Exception) {
            log.error("OCR failed for invoice {}: {}", invoice.id, e.message)
            invoice.status = InvoiceStatus.ERROR
            invoice.errorMessage = "OCR failed: ${e.message}"
            invoiceRepository.save(invoice)
            return InvoiceResponse.from(invoice)
        }

        // Step 2: Structured extraction
        try {
            invoice.status = InvoiceStatus.EXTRACTED
            invoiceRepository.save(invoice)

            // Phase A: Regex extraction (deterministic, no AI)
            val regexData = regexExtractionService.extract(invoice.rawText!!)

            // Phase B: Tabula table extraction for line items (PDF only)
            val tabulaItems = tableExtractionService.extractLineItems(Path.of(invoice.filePath))

            // Phase C: AI extraction if enabled and regex result is sparse
            val finalData = if (aiExtractionService.isAvailable() && isExtractionSparse(regexData)) {
                try {
                    log.info("Regex extraction sparse for invoice {}, using AI enrichment", invoice.id)
                    val aiData = aiExtractionService.extractInvoiceData(invoice.rawText!!)
                    invoice.aiUsed = true
                    mergeExtractions(regexData, aiData)
                } catch (e: Exception) {
                    log.warn("AI extraction failed for invoice {}, falling back to regex: {}", invoice.id, e.message)
                    regexData
                }
            } else {
                regexData
            }

            applyExtraction(invoice, finalData, tabulaItems)
            invoice.status = InvoiceStatus.EXTRACTED
            invoiceRepository.save(invoice)
        } catch (e: Exception) {
            log.error("Extraction failed for invoice {}: {}", invoice.id, e.message)
            invoice.status = InvoiceStatus.ERROR
            invoice.errorMessage = "Extraction failed: ${e.message}"
            invoiceRepository.save(invoice)
            return InvoiceResponse.from(invoice)
        }

        // Step 3: Auto-validate
        if (invoice.supplierName != null && invoice.amountTtc != null && invoice.invoiceNumber != null) {
            invoice.status = InvoiceStatus.READY_FOR_SAGE
        } else {
            invoice.status = InvoiceStatus.VALIDATION_FAILED
            invoice.errorMessage = buildString {
                append("Champs manquants :")
                if (invoice.supplierName == null) append(" fournisseur")
                if (invoice.invoiceNumber == null) append(" n°facture")
                if (invoice.amountTtc == null) append(" montant TTC")
            }
        }
        invoiceRepository.save(invoice)

        return InvoiceResponse.from(invoice)
    }

    /**
     * Check if regex extraction got too few fields to be useful.
     * If we have at least invoice number + one amount + supplier, regex is sufficient.
     */
    private fun isExtractionSparse(data: ExtractedInvoiceData): Boolean {
        val fieldCount = listOfNotNull(
            data.supplierName, data.invoiceNumber, data.amountHt, data.amountTtc,
            data.supplierIce, data.invoiceDate
        ).size
        return fieldCount < 3
    }

    /**
     * Merge regex and AI extractions: regex values take priority (deterministic),
     * AI fills in gaps.
     */
    private fun mergeExtractions(regex: ExtractedInvoiceData, ai: ExtractedInvoiceData): ExtractedInvoiceData {
        return ExtractedInvoiceData(
            supplierName = regex.supplierName ?: ai.supplierName,
            supplierIce = regex.supplierIce ?: ai.supplierIce,
            supplierIf = regex.supplierIf ?: ai.supplierIf,
            supplierRc = regex.supplierRc ?: ai.supplierRc,
            supplierPatente = regex.supplierPatente ?: ai.supplierPatente,
            supplierCnss = regex.supplierCnss ?: ai.supplierCnss,
            supplierAddress = regex.supplierAddress ?: ai.supplierAddress,
            supplierCity = regex.supplierCity ?: ai.supplierCity,
            clientName = regex.clientName ?: ai.clientName,
            clientIce = regex.clientIce ?: ai.clientIce,
            invoiceNumber = regex.invoiceNumber ?: ai.invoiceNumber,
            invoiceDate = regex.invoiceDate ?: ai.invoiceDate,
            amountHt = regex.amountHt ?: ai.amountHt,
            tvaRate = regex.tvaRate ?: ai.tvaRate,
            amountTva = regex.amountTva ?: ai.amountTva,
            amountTtc = regex.amountTtc ?: ai.amountTtc,
            discountAmount = regex.discountAmount ?: ai.discountAmount,
            discountPercent = regex.discountPercent ?: ai.discountPercent,
            currency = regex.currency ?: ai.currency,
            paymentMethod = regex.paymentMethod ?: ai.paymentMethod,
            paymentDueDate = regex.paymentDueDate ?: ai.paymentDueDate,
            bankName = regex.bankName ?: ai.bankName,
            bankRib = regex.bankRib ?: ai.bankRib,
            lineItems = if (!regex.lineItems.isNullOrEmpty()) regex.lineItems else ai.lineItems
        )
    }

    private fun applyExtraction(invoice: Invoice, data: ExtractedInvoiceData, tabulaItems: List<com.ocrsage.dto.ExtractedLineItem>) {
        // Supplier info
        invoice.supplierName = data.supplierName
        invoice.supplierIce = data.supplierIce
        invoice.supplierIf = data.supplierIf
        invoice.supplierRc = data.supplierRc
        invoice.supplierPatente = data.supplierPatente
        invoice.supplierCnss = data.supplierCnss
        invoice.supplierAddress = data.supplierAddress
        invoice.supplierCity = data.supplierCity

        // Client
        invoice.clientName = data.clientName
        invoice.clientIce = data.clientIce

        // Invoice details
        invoice.invoiceNumber = data.invoiceNumber
        invoice.invoiceDate = data.invoiceDate
        invoice.amountHt = data.amountHt
        invoice.tvaRate = data.tvaRate
        invoice.amountTva = data.amountTva
        invoice.amountTtc = data.amountTtc
        invoice.discountAmount = data.discountAmount
        invoice.discountPercent = data.discountPercent
        data.currency?.let { invoice.currency = it }

        // Payment
        invoice.paymentMethod = data.paymentMethod
        invoice.paymentDueDate = data.paymentDueDate
        invoice.bankName = data.bankName
        invoice.bankRib = data.bankRib

        // Line items: prefer Tabula (structured tables), fallback to AI-extracted
        val lineItemsSource = tabulaItems.ifEmpty { data.lineItems ?: emptyList() }
        for ((index, line) in lineItemsSource.withIndex()) {
            invoice.lineItems.add(InvoiceLineItem(
                invoice = invoice,
                lineNumber = line.lineNumber.takeIf { it > 0 } ?: (index + 1),
                description = line.description,
                quantity = line.quantity,
                unit = line.unit,
                unitPriceHt = line.unitPriceHt,
                tvaRate = line.tvaRate,
                tvaAmount = line.tvaAmount,
                totalHt = line.totalHt,
                totalTtc = line.totalTtc
            ))
        }
    }

    @Transactional(readOnly = true)
    fun getInvoice(id: Long): InvoiceResponse {
        val invoice = invoiceRepository.findById(id)
            .orElseThrow { NoSuchElementException("Invoice not found: $id") }
        return InvoiceResponse.from(invoice)
    }

    @Transactional(readOnly = true)
    fun getInvoiceFile(id: Long): Pair<String, String> {
        val invoice = invoiceRepository.findById(id)
            .orElseThrow { NoSuchElementException("Invoice not found: $id") }
        return Pair(invoice.filePath, invoice.fileName)
    }

    @Transactional
    fun updateInvoice(id: Long, update: InvoiceUpdateRequest): InvoiceResponse {
        val invoice = invoiceRepository.findById(id)
            .orElseThrow { NoSuchElementException("Invoice not found: $id") }

        // Apply all non-null fields from the update request
        update.supplierName?.let { invoice.supplierName = it.ifBlank { null } }
        update.supplierIce?.let { invoice.supplierIce = it.ifBlank { null } }
        update.supplierIf?.let { invoice.supplierIf = it.ifBlank { null } }
        update.supplierRc?.let { invoice.supplierRc = it.ifBlank { null } }
        update.supplierPatente?.let { invoice.supplierPatente = it.ifBlank { null } }
        update.supplierCnss?.let { invoice.supplierCnss = it.ifBlank { null } }
        update.supplierAddress?.let { invoice.supplierAddress = it.ifBlank { null } }
        update.supplierCity?.let { invoice.supplierCity = it.ifBlank { null } }
        update.clientName?.let { invoice.clientName = it.ifBlank { null } }
        update.clientIce?.let { invoice.clientIce = it.ifBlank { null } }
        update.invoiceNumber?.let { invoice.invoiceNumber = it.ifBlank { null } }
        update.invoiceDate?.let { invoice.invoiceDate = it }
        update.amountHt?.let { invoice.amountHt = it }
        update.tvaRate?.let { invoice.tvaRate = it }
        update.amountTva?.let { invoice.amountTva = it }
        update.amountTtc?.let { invoice.amountTtc = it }
        update.discountAmount?.let { invoice.discountAmount = it }
        update.discountPercent?.let { invoice.discountPercent = it }
        update.currency?.let { if (it.isNotBlank()) invoice.currency = it }
        update.paymentMethod?.let { invoice.paymentMethod = it.ifBlank { null } }
        update.paymentDueDate?.let { invoice.paymentDueDate = it }
        update.bankName?.let { invoice.bankName = it.ifBlank { null } }
        update.bankRib?.let { invoice.bankRib = it.ifBlank { null } }

        // Re-validate after correction
        if (invoice.supplierName != null && invoice.amountTtc != null && invoice.invoiceNumber != null) {
            invoice.status = InvoiceStatus.READY_FOR_SAGE
            invoice.errorMessage = null
        } else {
            invoice.status = InvoiceStatus.VALIDATION_FAILED
            invoice.errorMessage = buildString {
                append("Champs manquants :")
                if (invoice.supplierName == null) append(" fournisseur")
                if (invoice.invoiceNumber == null) append(" n°facture")
                if (invoice.amountTtc == null) append(" montant TTC")
            }
        }

        invoiceRepository.save(invoice)
        log.info("Invoice {} updated manually", id)
        return InvoiceResponse.from(invoice)
    }

    @Transactional(readOnly = true)
    fun listInvoices(pageable: Pageable): Page<InvoiceResponse> {
        return invoiceRepository.findAll(
            PageRequest.of(pageable.pageNumber, pageable.pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).map { InvoiceResponse.from(it) }
    }

    @Transactional
    fun syncToSage(id: Long): InvoiceResponse {
        val invoice = invoiceRepository.findById(id)
            .orElseThrow { NoSuchElementException("Invoice not found: $id") }

        if (invoice.status != InvoiceStatus.READY_FOR_SAGE) {
            throw IllegalStateException("Invoice ${invoice.id} is not ready for Sage sync (status: ${invoice.status})")
        }

        val result = erpConnectorFactory.syncInvoice(invoice)
        if (result.success) {
            invoice.sageSynced = true
            invoice.sageSyncDate = LocalDateTime.now()
            invoice.sageReference = result.reference
            invoice.status = InvoiceStatus.SAGE_SYNCED
        } else {
            invoice.status = InvoiceStatus.SAGE_SYNC_FAILED
            invoice.errorMessage = "Sage sync failed: ${result.error}"
        }
        invoiceRepository.save(invoice)

        return InvoiceResponse.from(invoice)
    }

    @Transactional(readOnly = true)
    fun getDashboardStats(): DashboardStats {
        val total = invoiceRepository.count()
        val byStatus = InvoiceStatus.entries.associateWith { invoiceRepository.countByStatus(it) }
        val synced = invoiceRepository.countBySageSynced(true)
        val totalAmount = invoiceRepository.sumProcessedAmounts()

        val topSuppliers = try {
            invoiceRepository.countBySupplier()
                .associate { row -> (row[0] as String) to (row[1] as Number).toLong() }
        } catch (e: Exception) {
            log.warn("Failed to fetch top suppliers for dashboard, returning empty map: {}", e.message)
            emptyMap()
        }

        return DashboardStats(
            totalInvoices = total,
            byStatus = byStatus.mapKeys { it.key.name },
            sageSynced = synced,
            pendingSync = byStatus[InvoiceStatus.READY_FOR_SAGE] ?: 0,
            totalProcessedAmount = totalAmount,
            topSuppliers = topSuppliers
        )
    }
}
