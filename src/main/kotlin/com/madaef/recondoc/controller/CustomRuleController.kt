package com.madaef.recondoc.controller

import com.madaef.recondoc.entity.dossier.CustomValidationRule
import com.madaef.recondoc.service.DossierService
import com.madaef.recondoc.service.validation.CustomRuleRequest
import com.madaef.recondoc.service.validation.CustomRuleService
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/custom-rules")
class CustomRuleController(
    private val service: CustomRuleService,
    private val dossierService: DossierService
) {

    @GetMapping
    fun list(): List<CustomRuleResponse> = service.list().map { it.toResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody req: CustomRuleRequest): CustomRuleResponse =
        service.create(req, currentUser()).toResponse()

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody req: CustomRuleRequest): CustomRuleResponse =
        service.update(id, req).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id)

    @PatchMapping("/{id}/toggle")
    fun toggle(@PathVariable id: UUID, @RequestBody body: Map<String, Boolean>): CustomRuleResponse {
        val enabled = body["enabled"] ?: throw IllegalArgumentException("champ 'enabled' requis")
        return service.toggle(id, enabled).toResponse()
    }

    /**
     * Dry-run a rule against a real dossier without persisting the result. Useful
     * for the "Tester" button in the Settings UI while the user tunes the prompt.
     */
    @PostMapping("/{id}/test")
    fun test(@PathVariable id: UUID, @RequestBody body: Map<String, String>): Map<String, Any?> {
        val rule = service.findById(id)
        val dossierId = UUID.fromString(body["dossierId"] ?: throw IllegalArgumentException("'dossierId' requis"))
        val dossier = dossierService.getDossierFull(dossierId)
        val result = service.evaluate(rule, dossier)
        return mapOf(
            "regle" to result.regle,
            "libelle" to result.libelle,
            "statut" to result.statut.name,
            "detail" to result.detail,
            "evidences" to result.evidences,
            "documentIds" to result.documentIds?.split(",")?.filter { it.isNotBlank() }
        )
    }

    private fun currentUser(): String? =
        runCatching { SecurityContextHolder.getContext().authentication?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "anonymousUser" }

    private fun CustomValidationRule.toResponse(): CustomRuleResponse = CustomRuleResponse(
        id = id!!,
        code = code,
        libelle = libelle,
        description = description,
        prompt = prompt,
        enabled = enabled,
        appliesToBC = appliesToBC,
        appliesToContractuel = appliesToContractuel,
        documentTypes = documentTypes?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
        severity = severity,
        requiredFields = requiredFields?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        createdBy = createdBy
    )
}

data class CustomRuleResponse(
    val id: UUID,
    val code: String,
    val libelle: String,
    val description: String?,
    val prompt: String,
    val enabled: Boolean,
    val appliesToBC: Boolean,
    val appliesToContractuel: Boolean,
    val documentTypes: List<String>,
    val severity: String,
    val requiredFields: List<String>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val createdBy: String?
)
