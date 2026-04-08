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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
        val docs = dossierService.uploadDocuments(id, files, type)
        for (doc in docs) {
            try {
                dossierService.processDocument(doc.id!!)
            } catch (e: Exception) {
                log.error("Processing failed for document {} in dossier {}: {}", doc.nomFichier, id, e.message)
            }
        }
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

    @GetMapping("/{id}/audit")
    fun getAudit(@PathVariable id: UUID): List<AuditLogResponse> {
        return dossierService.getAuditLog(id)
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
