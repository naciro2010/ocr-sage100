package com.ocrsage.service

import com.ocrsage.dto.DashboardStats
import com.ocrsage.dto.ExtractedInvoiceData
import com.ocrsage.dto.InvoiceResponse
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
    @Value("\${storage.upload-dir}") private val uploadDir: String,
    @Value("\${claude.api-key:}") private val claudeApiKey: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val aiEnabled: Boolean get() = claudeApiKey.isNotBlank()

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

        // Step 1: OCR text extraction (Tika)
        try {
            invoice.status = InvoiceStatus.OCR_IN_PROGRESS
            invoiceRepository.save(invoice)

            val rawText = ocrService.extractText(Files.newInputStream(filePath), fileName)
            invoice.rawText = rawText
            invoice.status = InvoiceStatus.OCR_COMPLETED
            invoiceRepository.save(invoice)
        } catch (e: Exception) {
            log.error("OCR failed for invoice {}: {}", invoice.id, e.message)
            invoice.status = InvoiceStatus.ERROR
            invoice.errorMessage = "OCR failed: ${e.message}"
            invoiceRepository.save(invoice)
            return InvoiceResponse.from(invoice)
        }

        // Step 2: Structured extraction
        try {
            invoice.status = InvoiceStatus.AI_EXTRACTION_IN_PROGRESS
            invoiceRepository.save(invoice)

            // Phase A: Regex extraction (deterministic, no AI)
            val regexData = regexExtractionService.extract(invoice.rawText!!)

            // Phase B: Tabula table extraction for line items (PDF only)
            val tabulaItems = tableExtractionService.extractLineItems(Path.of(invoice.filePath))

            // Phase C: If regex got sparse results and AI is configured, enrich with AI
            val finalData = if (aiEnabled && isExtractionSparse(regexData)) {
                log.info("Regex extraction sparse, enriching with AI for invoice {}", invoice.id)
                try {
                    val aiData = aiExtractionService.extractInvoiceData(invoice.rawText!!)
                    mergeExtractions(regexData, aiData)
                } catch (e: Exception) {
                    log.warn("AI enrichment failed, using regex-only results: {}", e.message)
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

    fun getInvoice(id: Long): InvoiceResponse {
        val invoice = invoiceRepository.findById(id)
            .orElseThrow { NoSuchElementException("Invoice not found: $id") }
        return InvoiceResponse.from(invoice)
    }

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

    fun getDashboardStats(): DashboardStats {
        val total = invoiceRepository.count()
        val byStatus = InvoiceStatus.entries.associateWith { invoiceRepository.countByStatus(it) }
        val synced = invoiceRepository.countBySageSynced(true)
        val totalAmount = invoiceRepository.sumProcessedAmounts()
        val topSuppliers = invoiceRepository.countBySupplier(PageRequest.of(0, 10))
            .associate { (it[0] as String) to (it[1] as Long) }

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
