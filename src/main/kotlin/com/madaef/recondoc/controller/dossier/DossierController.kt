package com.madaef.recondoc.controller.dossier

import com.madaef.recondoc.dto.dossier.*
import com.madaef.recondoc.entity.dossier.DossierType
import com.madaef.recondoc.entity.dossier.StatutDossier
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.DossierService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/dossiers")
class DossierController(private val dossierService: DossierService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateDossierRequest): DossierResponse {
        val dossier = dossierService.createDossier(request)
        return dossierService.getDossierResponse(dossier.id!!)
    }

    @GetMapping
    fun list(@PageableDefault(size = 20, sort = ["dateCreation"], direction = Sort.Direction.DESC) pageable: Pageable): Page<DossierListResponse> {
        return dossierService.listDossiers(pageable)
    }

    @GetMapping("/stats")
    fun stats(): DashboardStatsResponse {
        return dossierService.getDashboardStats()
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): DossierResponse {
        return dossierService.getDossierResponse(id)
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateDossierRequest): DossierResponse {
        dossierService.updateDossier(id, request)
        return dossierService.getDossierResponse(id)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        dossierService.deleteDossier(id)
    }

    @PatchMapping("/{id}/statut")
    fun changeStatut(@PathVariable id: UUID, @RequestBody request: ChangeStatutRequest): DossierResponse {
        dossierService.changeStatut(id, request)
        return dossierService.getDossierResponse(id)
    }

    @PostMapping("/{id}/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun uploadDocuments(
        @PathVariable id: UUID,
        @RequestParam("files") files: List<MultipartFile>,
        @RequestParam("type", required = false) type: TypeDocument?
    ): List<DocumentResponse> {
        dossierService.uploadDocuments(id, files, type)
        return dossierService.getDossierResponse(id).documents
    }

    @GetMapping("/{id}/documents")
    fun listDocuments(@PathVariable id: UUID): List<DocumentResponse> {
        return dossierService.getDossierResponse(id).documents
    }

    @PostMapping("/{id}/valider")
    fun validate(@PathVariable id: UUID): List<ValidationResultResponse> {
        return dossierService.validateDossier(id).map { it.toResponse() }
    }

    @GetMapping("/{id}/resultats-validation")
    fun getValidationResults(@PathVariable id: UUID): List<ValidationResultResponse> {
        return dossierService.getDossierResponse(id).resultatsValidation
    }

    @PostMapping("/{id}/documents/{docId}/reprocess")
    fun reprocessDocument(@PathVariable id: UUID, @PathVariable docId: UUID): DocumentResponse {
        dossierService.processDocument(docId)
        return dossierService.getDossierResponse(id).documents.first { it.id == docId }
    }

    @PatchMapping("/{id}/documents/{docId}/type")
    fun changeDocumentType(
        @PathVariable id: UUID, @PathVariable docId: UUID,
        @RequestBody body: Map<String, String>
    ): DocumentResponse {
        val newType = TypeDocument.valueOf(body["typeDocument"] ?: throw IllegalArgumentException("typeDocument required"))
        dossierService.changeDocumentType(docId, newType)
        return dossierService.getDossierResponse(id).documents.first { it.id == docId }
    }

    @DeleteMapping("/{id}/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDocument(@PathVariable id: UUID, @PathVariable docId: UUID) {
        dossierService.deleteDocument(id, docId)
    }

    @GetMapping("/{id}/audit")
    fun getAudit(@PathVariable id: UUID): List<AuditLogResponse> {
        return dossierService.getAuditLog(id)
    }

    @GetMapping("/{id}/documents/{docId}/file")
    fun downloadDocumentFile(@PathVariable id: UUID, @PathVariable docId: UUID): ResponseEntity<Resource> {
        val (filePath, fileName) = dossierService.getDocumentFile(id, docId)
        val resource = FileSystemResource(filePath)
        val contentType = when {
            fileName.endsWith(".pdf", true) -> MediaType.APPLICATION_PDF
            fileName.endsWith(".png", true) -> MediaType.IMAGE_PNG
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> MediaType.IMAGE_JPEG
            else -> MediaType.APPLICATION_OCTET_STREAM
        }
        return ResponseEntity.ok()
            .contentType(contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}\"")
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
            .body(resource)
    }

    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) statut: StatutDossier?,
        @RequestParam(required = false) type: DossierType?,
        @RequestParam(required = false) fournisseur: String?,
        @PageableDefault(size = 20, sort = ["dateCreation"], direction = Sort.Direction.DESC) pageable: Pageable
    ): Page<DossierListResponse> {
        return dossierService.searchDossiers(statut, type, fournisseur, pageable)
    }
}
