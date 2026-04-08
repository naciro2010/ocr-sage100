package com.ocrsage.controller.dossier

import com.ocrsage.dto.dossier.*
import com.ocrsage.entity.dossier.TypeDocument
import com.ocrsage.service.DossierService
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateDossierRequest): DossierResponse {
        val dossier = dossierService.createDossier(request)
        return dossierService.buildFullResponse(dossier)
    }

    @GetMapping
    fun list(@PageableDefault(size = 20, sort = ["dateCreation"], direction = Sort.Direction.DESC) pageable: Pageable): Page<DossierListResponse> {
        return dossierService.listDossiers(pageable)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): DossierResponse {
        val dossier = dossierService.getDossier(id)
        return dossierService.buildFullResponse(dossier)
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateDossierRequest): DossierResponse {
        val dossier = dossierService.updateDossier(id, request)
        return dossierService.buildFullResponse(dossier)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        dossierService.deleteDossier(id)
    }

    @PatchMapping("/{id}/statut")
    fun changeStatut(@PathVariable id: UUID, @RequestBody request: ChangeStatutRequest): DossierResponse {
        val dossier = dossierService.changeStatut(id, request)
        return dossierService.buildFullResponse(dossier)
    }

    // === Documents ===

    @PostMapping("/{id}/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun uploadDocuments(
        @PathVariable id: UUID,
        @RequestParam("files") files: List<MultipartFile>,
        @RequestParam("type", required = false) type: TypeDocument?
    ): List<DocumentResponse> {
        val docs = dossierService.uploadDocuments(id, files, type)

        // Process each document (extraction)
        docs.forEach { doc ->
            try {
                dossierService.processDocument(doc.id!!)
            } catch (e: Exception) {
                // Error already stored in document
            }
        }

        // Reload to get updated data
        val dossier = dossierService.getDossier(id)
        return dossier.documents.map { it.toResponse() }
    }

    @GetMapping("/{id}/documents")
    fun listDocuments(@PathVariable id: UUID): List<DocumentResponse> {
        val dossier = dossierService.getDossier(id)
        return dossier.documents.map { it.toResponse() }
    }

    // === Validation ===

    @PostMapping("/{id}/valider")
    fun validate(@PathVariable id: UUID): List<ValidationResultResponse> {
        return dossierService.validateDossier(id).map { it.toResponse() }
    }

    @GetMapping("/{id}/resultats-validation")
    fun getValidationResults(@PathVariable id: UUID): List<ValidationResultResponse> {
        val dossier = dossierService.getDossier(id)
        return dossier.resultatsValidation.map { it.toResponse() }
    }
}
