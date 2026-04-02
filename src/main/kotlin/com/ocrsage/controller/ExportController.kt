package com.ocrsage.controller

import com.ocrsage.repository.InvoiceRepository
import com.ocrsage.service.*
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/invoices")
class ExportController(
    private val exportService: ExportService,
    private val batchProcessingService: BatchProcessingService,
    private val validationService: ValidationService,
    private val invoiceRepository: InvoiceRepository
) {

    // --- Export endpoints ---

    @GetMapping("/export/csv")
    fun exportCsv(@RequestParam ids: List<Long>): ResponseEntity<ByteArray> {
        val data = exportService.exportToCsv(ids)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"factures_export.csv\"")
            .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
            .body(data)
    }

    @GetMapping("/export/json")
    fun exportJson(@RequestParam ids: List<Long>): ResponseEntity<ByteArray> {
        val data = exportService.exportToJson(ids)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"factures_export.json\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(data)
    }

    @GetMapping("/{id}/export/ubl")
    fun exportUbl(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val data = exportService.exportToUblXml(id)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"facture_${id}_ubl.xml\"")
            .contentType(MediaType.APPLICATION_XML)
            .body(data)
    }

    @GetMapping("/{id}/export/edi")
    fun exportEdi(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val data = exportService.exportToEdiFactur(id)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"facture_${id}.edi\"")
            .header(HttpHeaders.CONTENT_TYPE, "application/edifact")
            .body(data)
    }

    // --- Batch endpoints ---

    @PostMapping("/batch", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.OK)
    fun batchUpload(@RequestParam("files") files: List<MultipartFile>): BatchResult {
        require(files.isNotEmpty()) { "At least one file must be provided" }
        return batchProcessingService.processBatch(files)
    }

    @PostMapping("/batch-sync")
    fun batchSync(@RequestBody request: BatchSyncRequest): BatchSyncResult {
        require(request.invoiceIds.isNotEmpty()) { "At least one invoice ID must be provided" }
        return batchProcessingService.batchSyncToSage(request.invoiceIds, request.erpType)
    }

    @GetMapping("/batch/queue")
    fun getProcessingQueue(): List<QueueItem> {
        return batchProcessingService.getProcessingQueue()
    }

    // --- Validation endpoint ---

    @GetMapping("/{id}/validate")
    fun validateInvoice(@PathVariable id: Long): ValidationResult {
        val invoice = invoiceRepository.findById(id)
            .orElseThrow { NoSuchElementException("Invoice not found: $id") }
        return validationService.validateInvoice(invoice)
    }
}

data class BatchSyncRequest(
    val invoiceIds: List<Long>,
    val erpType: String = "sage1000"
)
