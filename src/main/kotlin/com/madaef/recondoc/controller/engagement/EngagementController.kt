package com.madaef.recondoc.controller.engagement

import com.madaef.recondoc.dto.engagement.*
import com.madaef.recondoc.entity.engagement.StatutEngagement
import com.madaef.recondoc.service.engagement.EngagementService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/engagements")
class EngagementController(
    private val engagementService: EngagementService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateEngagementRequest): EngagementResponse {
        val engagement = engagementService.create(request)
        return engagementService.get(engagement.id!!)
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) statut: StatutEngagement?,
        @RequestParam(required = false) fournisseur: String?,
        @RequestParam(required = false) reference: String?,
        @PageableDefault(size = 20, sort = ["dateCreation"], direction = Sort.Direction.DESC) pageable: Pageable
    ): Page<EngagementListItem> {
        return engagementService.list(statut, fournisseur?.takeIf { it.isNotBlank() }, reference?.takeIf { it.isNotBlank() }, pageable)
    }

    @GetMapping("/stats")
    fun stats(): EngagementStats = engagementService.stats()

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): EngagementResponse = engagementService.get(id)

    @GetMapping("/{id}/tree")
    fun tree(@PathVariable id: UUID): EngagementTreeNode = engagementService.tree(id)

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateEngagementRequest): EngagementResponse {
        engagementService.update(id, request)
        return engagementService.get(id)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        engagementService.delete(id)
    }

    @PostMapping("/{id}/dossiers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun attachDossier(@PathVariable id: UUID, @RequestBody request: AttachDossierRequest) {
        engagementService.attachDossier(id, request.dossierId)
    }

    @DeleteMapping("/dossiers/{dossierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun detachDossier(@PathVariable dossierId: UUID) {
        engagementService.detachDossier(dossierId)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Requete invalide")))

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(e: IllegalStateException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to (e.message ?: "Conflit")))

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to (e.message ?: "Non trouve")))
}
