package com.ocrsage.service

import com.ocrsage.dto.DashboardStats
import com.ocrsage.dto.ExtractedInvoiceData
import com.ocrsage.dto.ExtractedLineItem
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

/**
 * Value object carrying the result of the extraction phase so it can be
 * passed across transaction boundaries without holding a DB connection.
 */
data class ExtractionResult(
    val data: ExtractedInvoiceData,
    val tabulaItems: List<ExtractedLineItem>,
    val aiUsed: Boolean
)

/**
 * Handles all short-lived database transactions for the invoice processing
 * pipeline. Kept as a separate Spring bean so that @Transactional is applied
 * via the Spring AOP proxy — self-invocation within InvoiceService would
 * bypass the proxy and leave transactions inactive.
 */
@Service
class InvoiceTransactionService(
    private val invoiceRepository: InvoiceRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun saveInitialInvoice(fileName: String, filePath: String): Long {
        val invoice = Invoice(fileName = fileName, filePath = filePath)
        invoiceRepository.save(invoice)
        return invoice.id!!
    }

    @Transactional
    fun markOcrInProgress(invoiceId: Long) {
        val invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow { NoSuchElementException("Invoice not found: $invoiceId") }
        invoice.status = InvoiceStatus.OCR_IN_PROGRESS
        invoiceRepository.save(invoice)
    }

    @Transactional
    fun saveOcrResults(invoiceId: Long, ocrResult: OcrService.OcrResult): String {
        val invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow { NoSuchElementException("Invoice not found: $invoiceId") }
        invoice.rawText = ocrResult.text
        invoice.ocrEngine = ocrResult.engine.name
        invoice.ocrConfidence = ocrResult.confidence.takeIf { it >= 0 }
        invoice.ocrPageCount = ocrResult.pageCount
        invoice.status = InvoiceStatus.OCR_COMPLETED
        invoiceRepository.save(invoice)
        return ocrResult.text
    }

    @Transactional
    fun saveExtractionResults(invoiceId: Long, result: ExtractionResult): InvoiceResponse {
        val invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow { NoSuchElementException("Invoice not found: $invoiceId") }

        invoice.aiUsed = result.aiUsed
        applyExtraction(invoice, result.data, result.tabulaItems)
        invoice.status = InvoiceStatus.EXTRACTED

        // Auto-validate
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

    @Transactional
    fun markError(invoiceId: Long, message: String): InvoiceResponse {
        val invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow { NoSuchElementException("Invoice not found: $invoiceId") }
        invoice.status = InvoiceStatus.ERROR
        invoice.errorMessage = message
        invoiceRepository.save(invoice)
        return InvoiceResponse.from(invoice)
    }

    private fun applyExtraction(
        invoice: Invoice,
        data: ExtractedInvoiceData,
        tabulaItems: List<ExtractedLineItem>
    ) {
        invoice.supplierName = data.supplierName
        invoice.supplierIce = data.supplierIce
        invoice.supplierIf = data.supplierIf
        invoice.supplierRc = data.supplierRc
        invoice.supplierPatente = data.supplierPatente
        invoice.supplierCnss = data.supplierCnss
        invoice.supplierAddress = data.supplierAddress
        invoice.supplierCity = data.supplierCity
        invoice.clientName = data.clientName
        invoice.clientIce = data.clientIce
        invoice.invoiceNumber = data.invoiceNumber
        invoice.invoiceDate = data.invoiceDate
        invoice.amountHt = data.amountHt
        invoice.tvaRate = data.tvaRate
        invoice.amountTva = data.amountTva
        invoice.amountTtc = data.amountTtc
        invoice.discountAmount = data.discountAmount
        invoice.discountPercent = data.discountPercent
        data.currency?.let { invoice.currency = it }
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
}

@Service
class InvoiceService(
    private val invoiceRepository: InvoiceRepository,
    private val invoiceTxService: InvoiceTransactionService,
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

    /**
     * Orchestrates the full upload-and-process pipeline without holding a single
     * long-lived database transaction. Each DB interaction is its own short
     * transaction (delegated to [InvoiceTransactionService]) so HikariCP
     * connections are released promptly between the long-running OCR and AI
     * extraction steps.
     *
     * Pipeline:
     *   TX1 → save initial record
     *   TX2 → mark OCR_IN_PROGRESS
     *   [OCR — no DB connection held]
     *   TX3 → save OCR results
     *   [Regex + table + AI extraction — no DB connection held]
     *   TX4 → save extraction results + auto-validate
     */
    fun uploadAndProcess(file: MultipartFile): InvoiceResponse {
        val uploadPath = Path.of(uploadDir)
        Files.createDirectories(uploadPath)

        val fileName = "${System.currentTimeMillis()}_${file.originalFilename}"
        val filePath = uploadPath.resolve(fileName)
        file.transferTo(filePath)

        // TX 1: persist the initial invoice record
        val invoiceId = invoiceTxService.saveInitialInvoice(file.originalFilename ?: "unknown", filePath.toString())
        log.info("Invoice uploaded: {} (id={})", fileName, invoiceId)

        // TX 2: mark OCR as in-progress — connection released before OCR starts
        invoiceTxService.markOcrInProgress(invoiceId)

        // OCR — runs entirely outside any transaction; no DB connection held
        val ocrResult = try {
            ocrService.extractWithDetails(Files.newInputStream(filePath), fileName, filePath)
        } catch (e: Exception) {
            log.error("OCR failed for invoice {}: {}", invoiceId, e.message)
            return invoiceTxService.markError(invoiceId, "OCR failed: ${e.message}")
        }

        // TX 3: persist OCR results — connection released before extraction starts
        log.info("OCR completed for invoice {} (engine={}, {} chars, {} pages)",
            invoiceId, ocrResult.engine, ocrResult.text.length, ocrResult.pageCount)
        val rawText = invoiceTxService.saveOcrResults(invoiceId, ocrResult)

        // Extraction — runs entirely outside any transaction; no DB connection held
        val extractionResult = try {
            performExtraction(invoiceId, rawText, filePath)
        } catch (e: Exception) {
            log.error("Extraction failed for invoice {}: {}", invoiceId, e.message)
            return invoiceTxService.markError(invoiceId, "Extraction failed: ${e.message}")
        }

        // TX 4: persist extraction results and run auto-validation
        return invoiceTxService.saveExtractionResults(invoiceId, extractionResult)
    }

    /**
     * Pure computation — regex, table extraction, and optional AI call.
     * No DB access, no transaction. Returns an [ExtractionResult] value object
     * that the caller persists in its own short transaction.
     */
    private fun performExtraction(invoiceId: Long, rawText: String, filePath: Path): ExtractionResult {
        // Phase A: Regex extraction (deterministic, no AI)
        val regexData = regexExtractionService.extract(rawText)

        // Phase B: Tabula table extraction for line items (PDF only)
        val tabulaItems = tableExtractionService.extractLineItems(filePath)

        // Phase C: AI extraction if enabled and regex result is sparse
        var aiUsed = false
        val finalData = if (aiExtractionService.isAvailable() && isExtractionSparse(regexData)) {
            try {
                log.info("Regex extraction sparse for invoice {}, using AI enrichment", invoiceId)
                val aiData = aiExtractionService.extractInvoiceData(rawText)
                aiUsed = true
                mergeExtractions(regexData, aiData)
            } catch (e: Exception) {
                log.warn("AI extraction failed for invoice {}, falling back to regex: {}", invoiceId, e.message)
                regexData
            }
        } else {
            regexData
        }

        return ExtractionResult(data = finalData, tabulaItems = tabulaItems, aiUsed = aiUsed)
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
