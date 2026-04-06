package com.ocrsage.controller

import com.ocrsage.dto.DashboardStats
import com.ocrsage.dto.InvoiceResponse
import com.ocrsage.dto.InvoiceUpdateRequest
import com.ocrsage.service.InvoiceService
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path

@RestController
@RequestMapping("/api/invoices")
class InvoiceController(private val invoiceService: InvoiceService) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(@RequestParam("file") file: MultipartFile): InvoiceResponse {
        require(file.size > 0) { "File must not be empty" }
        return invoiceService.uploadAndProcess(file)
    }

    @GetMapping
    fun list(pageable: Pageable): Page<InvoiceResponse> {
        return invoiceService.listInvoices(pageable)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): InvoiceResponse {
        return invoiceService.getInvoice(id)
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody update: InvoiceUpdateRequest): InvoiceResponse {
        return invoiceService.updateInvoice(id, update)
    }

    @PostMapping("/{id}/sync")
    fun syncToSage(@PathVariable id: Long): InvoiceResponse {
        return invoiceService.syncToSage(id)
    }

    @GetMapping("/dashboard")
    fun dashboard(): DashboardStats {
        return invoiceService.getDashboardStats()
    }

    @GetMapping("/{id}/file")
    fun getFile(@PathVariable id: Long): ResponseEntity<Resource> {
        val invoice = invoiceService.getInvoiceFile(id)
        val filePath = Path.of(invoice.first)

        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build()
        }

        val fileName = invoice.second
        val contentType = when {
            fileName.lowercase().endsWith(".pdf") -> MediaType.APPLICATION_PDF
            fileName.lowercase().endsWith(".png") -> MediaType.IMAGE_PNG
            fileName.lowercase().matches(Regex(".*\\.jpe?g$")) -> MediaType.IMAGE_JPEG
            fileName.lowercase().matches(Regex(".*\\.tiff?$")) -> MediaType.parseMediaType("image/tiff")
            else -> MediaType.APPLICATION_OCTET_STREAM
        }

        return ResponseEntity.ok()
            .contentType(contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$fileName\"")
            .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
            .body(FileSystemResource(filePath))
    }
}
