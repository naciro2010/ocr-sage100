package com.ocrsage.controller

import com.ocrsage.repository.InvoiceRepository
import com.ocrsage.service.*
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/export")
class ExportController(
    private val exportService: ExportService
) {

    @PostMapping("/csv")
    fun exportCsv(@RequestBody request: ExportRequest): ResponseEntity<ByteArray> {
        val data = exportService.exportToCsv(request.ids)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"factures_export.csv\"")
            .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
            .body(data)
    }

    @PostMapping("/json")
    fun exportJson(@RequestBody request: ExportRequest): ResponseEntity<ByteArray> {
        val data = exportService.exportToJson(request.ids)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"factures_export.json\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(data)
    }

    @GetMapping("/ubl/{id}")
    fun exportUbl(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val data = exportService.exportToUblXml(id)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"facture_${id}_ubl.xml\"")
            .contentType(MediaType.APPLICATION_XML)
            .body(data)
    }

    @GetMapping("/edi/{id}")
    fun exportEdi(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val data = exportService.exportToEdiFactur(id)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"facture_${id}.edi\"")
            .header(HttpHeaders.CONTENT_TYPE, "application/edifact")
            .body(data)
    }
}

data class ExportRequest(val ids: List<Long>)

@RestController
@RequestMapping("/api/invoices")
class BatchController(
    private val batchProcessingService: BatchProcessingService,
    private val validationService: ValidationService,
    private val invoiceRepository: InvoiceRepository
) {

    @PostMapping("/batch", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.OK)
    fun batchUpload(@RequestParam("files") files: List<MultipartFile>): BatchResult {
        require(files.isNotEmpty()) { "At least one file must be provided" }
        return batchProcessingService.processBatch(files)
    }

    @PostMapping("/batch-sync")
    fun batchSync(@RequestBody request: BatchSyncRequest): BatchSyncResult {
        require(request.ids.isNotEmpty()) { "At least one invoice ID must be provided" }
        return batchProcessingService.batchSyncToSage(request.ids, request.erpType)
    }

    @GetMapping("/batch/queue")
    fun getProcessingQueue(): List<QueueItem> {
        return batchProcessingService.getProcessingQueue()
    }

    @GetMapping("/{id}/validate")
    @Transactional(readOnly = true)
    fun validateInvoice(@PathVariable id: Long): ValidationResult {
        val invoice = invoiceRepository.findById(id)
            .orElseThrow { NoSuchElementException("Invoice not found: $id") }
        return validationService.validateInvoice(invoice)
    }
}

data class BatchSyncRequest(
    val ids: List<Long>,
    val erpType: String = "SAGE_1000"
)
