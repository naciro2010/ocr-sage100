package com.madaef.recondoc.service

import com.madaef.recondoc.dto.DashboardStats
import com.madaef.recondoc.dto.ExtractedInvoiceData
import com.madaef.recondoc.dto.ExtractedLineItem
import com.madaef.recondoc.dto.InvoiceResponse
import com.madaef.recondoc.dto.InvoiceUpdateRequest
import com.madaef.recondoc.entity.Invoice
import com.madaef.recondoc.entity.InvoiceLineItem
import com.madaef.recondoc.entity.InvoiceStatus
import com.madaef.recondoc.repository.InvoiceRepository
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
        val start = System.currentTimeMillis()
        val invoice = Invoice(fileName = fileName, filePath = filePath)
        invoiceRepository.save(invoice)
        log.info("[TX1] Saved initial invoice id={} file={} in {}ms", invoice.id, fileName, System.currentTimeMillis() - start)
        return invoice.id!!
    }

    @Transactional
    fun markOcrInProgress(invoiceId: Long) {
        val invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow { NoSuchElementException("Invoice not found: $invoiceId") }
        invoice.status = InvoiceStatus.OCR_IN_PROGRESS
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

        return InvoiceResponse.from(invoice)
    }

    @Transactional
    fun markError(invoiceId: Long, message: String): InvoiceResponse {
        val invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow { NoSuchElementException("Invoice not found: $invoiceId") }
        invoice.status = InvoiceStatus.ERROR
        invoice.errorMessage = message
        log.error("Invoice id={} marked ERROR: {}", invoiceId, message)
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
    private val objectStorageService: ObjectStorageService,
    @Value("\${storage.upload-dir}") private val uploadDir: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun init() {
        val uploadPath = Path.of(uploadDir)
        Files.createDirectories(uploadPath)
        log.info("Upload directory ready: {} (S3 enabled: {})", uploadPath.toAbsolutePath(), objectStorageService.isEnabled())
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
        val fileName = "${System.currentTimeMillis()}_${file.originalFilename}"
        val fileBytes = file.bytes

        // Store file: S3 bucket or local filesystem
        val storagePath: String
        if (objectStorageService.isEnabled()) {
            val s3Key = "invoices/$fileName"
            val contentType = file.contentType ?: "application/octet-stream"
            objectStorageService.upload(s3Key, fileBytes, contentType)
            storagePath = "s3://$s3Key"
        } else {
            val uploadPath = Path.of(uploadDir)
            Files.createDirectories(uploadPath)
            val localPath = uploadPath.resolve(fileName)
            Files.write(localPath, fileBytes)
            storagePath = localPath.toString()
        }

        // TX 1: persist the initial invoice record
        val invoiceId = invoiceTxService.saveInitialInvoice(file.originalFilename ?: "unknown", storagePath)
        log.info("Invoice uploaded: {} (id={}, storage={})", fileName, invoiceId, if (objectStorageService.isEnabled()) "s3" else "local")

        // TX 2: mark OCR as in-progress
        invoiceTxService.markOcrInProgress(invoiceId)

        // Write to temp file for OCR processing (needs file path)
        val tempFile = Files.createTempFile("ocr-", "-$fileName")
        try {
            Files.write(tempFile, fileBytes)

            val ocrResult = try {
                ocrService.extractWithDetails(Files.newInputStream(tempFile), fileName, tempFile)
            } catch (e: Exception) {
                log.error("OCR failed for invoice {}: {}", invoiceId, e.message)
                return invoiceTxService.markError(invoiceId, "OCR failed: ${e.message}")
            }

            val rawText = invoiceTxService.saveOcrResults(invoiceId, ocrResult)

            val extractionResult = try {
                performExtraction(invoiceId, rawText, tempFile)
            } catch (e: Exception) {
                log.error("Extraction failed for invoice {}: {}", invoiceId, e.message)
                return invoiceTxService.markError(invoiceId, "Extraction failed: ${e.message}")
            }

            return invoiceTxService.saveExtractionResults(invoiceId, extractionResult)
        } finally {
            Files.deleteIfExists(tempFile)
        }
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
        val start = System.currentTimeMillis()
        val invoice = invoiceRepository.findById(id)
            .orElseThrow { NoSuchElementException("Invoice not found: $id") }
        val result = InvoiceResponse.from(invoice)
        log.debug("getInvoice id={} in {}ms", id, System.currentTimeMillis() - start)
        return result
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

        return InvoiceResponse.from(invoice)
    }

    @Transactional(readOnly = true)
    fun listInvoices(pageable: Pageable): Page<InvoiceResponse> {
        val sorted = PageRequest.of(pageable.pageNumber, pageable.pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        val page = invoiceRepository.findAllPaged(sorted)
        if (page.content.isNotEmpty()) {
            invoiceRepository.fetchWithLineItems(page.content)
        }
        return page.map { InvoiceResponse.from(it) }
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
            log.error("Invoice {} Sage sync failed: {}", id, result.error)
        }

        return InvoiceResponse.from(invoice)
    }

    @Transactional(readOnly = true)
    fun getDashboardStats(): DashboardStats {
        val emptyStats = DashboardStats(
            totalInvoices = 0, byStatus = emptyMap(), sageSynced = 0,
            pendingSync = 0, totalProcessedAmount = java.math.BigDecimal.ZERO, topSuppliers = emptyMap()
        )

        val row = invoiceRepository.getDashboardCounts().firstOrNull() ?: return emptyStats

        val byStatus = mapOf(
            "UPLOADED" to (row[1] as Number).toLong(),
            "OCR_IN_PROGRESS" to (row[2] as Number).toLong(),
            "OCR_COMPLETED" to (row[3] as Number).toLong(),
            "AI_EXTRACTION_IN_PROGRESS" to (row[4] as Number).toLong(),
            "EXTRACTED" to (row[5] as Number).toLong(),
            "VALIDATION_FAILED" to (row[6] as Number).toLong(),
            "READY_FOR_SAGE" to (row[7] as Number).toLong(),
            "SAGE_SYNCED" to (row[8] as Number).toLong(),
            "SAGE_SYNC_FAILED" to (row[9] as Number).toLong(),
            "ERROR" to (row[10] as Number).toLong()
        )

        val topSuppliers = invoiceRepository.countBySupplier()
            .associate { r -> (r[0] as String) to (r[1] as Number).toLong() }

        return DashboardStats(
            totalInvoices = (row[0] as Number).toLong(),
            byStatus = byStatus,
            sageSynced = (row[11] as Number).toLong(),
            pendingSync = byStatus["READY_FOR_SAGE"] ?: 0,
            totalProcessedAmount = row[12] as? java.math.BigDecimal ?: java.math.BigDecimal.ZERO,
            topSuppliers = topSuppliers
        )
    }
}
