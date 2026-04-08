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
        return dossierService.getDossierResponse(dossier.id!!)
    }

    @GetMapping
    fun list(@PageableDefault(size = 20, sort = ["dateCreation"], direction = Sort.Direction.DESC) pageable: Pageable): Page<DossierListResponse> {
        return dossierService.listDossiers(pageable)
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
        docs.forEach { doc ->
            try { dossierService.processDocument(doc.id!!) } catch (_: Exception) {}
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
}
