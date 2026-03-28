package com.ocrsage.service

import com.ocrsage.dto.DashboardStats
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
    private val aiExtractionService: AiExtractionService,
    private val sage1000Service: Sage1000Service,
    @Value("\${storage.upload-dir}") private val uploadDir: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

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

        // Step 1: OCR extraction
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

        // Step 2: AI structured extraction
        try {
            invoice.status = InvoiceStatus.AI_EXTRACTION_IN_PROGRESS
            invoiceRepository.save(invoice)

            val extracted = aiExtractionService.extractInvoiceData(invoice.rawText!!)

            // Supplier info
            invoice.supplierName = extracted.supplierName
            invoice.supplierIce = extracted.supplierIce
            invoice.supplierIf = extracted.supplierIf
            invoice.supplierRc = extracted.supplierRc
            invoice.supplierPatente = extracted.supplierPatente
            invoice.supplierCnss = extracted.supplierCnss
            invoice.supplierAddress = extracted.supplierAddress
            invoice.supplierCity = extracted.supplierCity

            // Client info
            invoice.clientName = extracted.clientName
            invoice.clientIce = extracted.clientIce

            // Invoice details
            invoice.invoiceNumber = extracted.invoiceNumber
            invoice.invoiceDate = extracted.invoiceDate
            invoice.amountHt = extracted.amountHt
            invoice.tvaRate = extracted.tvaRate
            invoice.amountTva = extracted.amountTva
            invoice.amountTtc = extracted.amountTtc
            invoice.discountAmount = extracted.discountAmount
            invoice.discountPercent = extracted.discountPercent
            extracted.currency?.let { invoice.currency = it }

            // Payment
            invoice.paymentMethod = extracted.paymentMethod
            invoice.paymentDueDate = extracted.paymentDueDate
            invoice.bankName = extracted.bankName
            invoice.bankRib = extracted.bankRib

            // Line items
            extracted.lineItems?.forEachIndexed { index, line ->
                val item = InvoiceLineItem(
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
                )
                invoice.lineItems.add(item)
            }

            invoice.status = InvoiceStatus.EXTRACTED
            invoiceRepository.save(invoice)
        } catch (e: Exception) {
            log.error("AI extraction failed for invoice {}: {}", invoice.id, e.message)
            invoice.status = InvoiceStatus.ERROR
            invoice.errorMessage = "AI extraction failed: ${e.message}"
            invoiceRepository.save(invoice)
            return InvoiceResponse.from(invoice)
        }

        // Auto-validate
        if (invoice.supplierName != null && invoice.amountTtc != null && invoice.invoiceNumber != null) {
            invoice.status = InvoiceStatus.READY_FOR_SAGE
        } else {
            invoice.status = InvoiceStatus.VALIDATION_FAILED
            invoice.errorMessage = "Missing required fields after extraction"
        }
        invoiceRepository.save(invoice)

        return InvoiceResponse.from(invoice)
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

        val result = sage1000Service.syncInvoice(invoice)
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
