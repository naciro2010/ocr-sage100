package com.madaef.recondoc.service.validation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.CustomValidationRuleRepository
import com.madaef.recondoc.repository.dossier.DossierRuleOverrideRepository
import com.madaef.recondoc.repository.dossier.ResultatValidationRepository
import com.madaef.recondoc.repository.dossier.RuleConfigRepository
import com.madaef.recondoc.service.extraction.LlmExtractionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * CRUD + LLM evaluation for user-defined validation rules.
 *
 * The LLM contract is strict: given the rule prompt and a bundle of extracted
 * dossier data, Claude must answer with a JSON of shape
 *   { "statut": "CONFORME"|"NON_CONFORME"|"AVERTISSEMENT"|"NON_APPLICABLE",
 *     "detail": "...", "evidences": [{champ, valeur, documentType}],
 *     "needsMoreInfo": false, "questions": [] }
 * When `needsMoreInfo` is true and fields are missing, the rule reports
 * NON_APPLICABLE with the questions surfaced to the user in `detail`.
 */
@Service
class CustomRuleService(
    private val repo: CustomValidationRuleRepository,
    private val llm: LlmExtractionService,
    private val objectMapper: ObjectMapper,
    private val resultatRepository: ResultatValidationRepository,
    private val overrideRepo: DossierRuleOverrideRepository,
    private val ruleConfigRepo: RuleConfigRepository,
    private val ruleConfigCache: RuleConfigCache
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(): List<CustomValidationRule> = repo.findAllByOrderByCodeAsc()

    @Transactional(readOnly = true)
    fun listEnabled(): List<CustomValidationRule> = repo.findByEnabledTrueOrderByCodeAsc()

    @Transactional(readOnly = true)
    fun findByCode(code: String): CustomValidationRule? = repo.findByCode(code)

    @Transactional(readOnly = true)
    fun findById(id: UUID): CustomValidationRule =
        repo.findById(id).orElseThrow { IllegalArgumentException("Regle custom introuvable: $id") }

    @Transactional
    fun create(req: CustomRuleRequest, user: String?): CustomValidationRule {
        validateRequest(req)
        val code = nextCode()
        val rule = CustomValidationRule(
            code = code,
            libelle = req.libelle.trim(),
            description = req.description?.trim(),
            prompt = req.prompt.trim(),
            enabled = req.enabled,
            appliesToBC = req.appliesToBC,
            appliesToContractuel = req.appliesToContractuel,
            documentTypes = req.documentTypes?.joinToString(",")?.ifBlank { null },
            severity = req.severity.ifBlank { "NON_CONFORME" },
            requiredFields = req.requiredFields?.joinToString(",")?.ifBlank { null },
            createdBy = user
        )
        return repo.save(rule)
    }

    @Transactional
    fun update(id: UUID, req: CustomRuleRequest): CustomValidationRule {
        validateRequest(req)
        val rule = repo.findById(id).orElseThrow { IllegalArgumentException("Regle custom introuvable: $id") }
        rule.libelle = req.libelle.trim()
        rule.description = req.description?.trim()
        rule.prompt = req.prompt.trim()
        rule.enabled = req.enabled
        rule.appliesToBC = req.appliesToBC
        rule.appliesToContractuel = req.appliesToContractuel
        rule.documentTypes = req.documentTypes?.joinToString(",")?.ifBlank { null }
        rule.severity = req.severity.ifBlank { "NON_CONFORME" }
        rule.requiredFields = req.requiredFields?.joinToString(",")?.ifBlank { null }
        rule.updatedAt = LocalDateTime.now()
        return repo.save(rule)
    }

    @Transactional
    fun delete(id: UUID) {
        val rule = repo.findById(id).orElseThrow { IllegalArgumentException("Regle custom introuvable: $id") }
        val code = rule.code
        // Cascade cleanup so the rule leaves no dangling rows in rule_config,
        // dossier_rule_override or resultat_validation (which would show up as
        // "orphan results" in the UI otherwise).
        resultatRepository.deleteByRegle(code)
        overrideRepo.deleteByRegle(code)
        ruleConfigRepo.findByRegle(code)?.let {
            ruleConfigRepo.delete(it)
            ruleConfigCache.evictGlobal()
        }
        ruleConfigCache.evictAllOverrides()
        repo.deleteById(id)
    }

    @Transactional
    fun toggle(id: UUID, enabled: Boolean): CustomValidationRule {
        val rule = repo.findById(id).orElseThrow { IllegalArgumentException("Regle custom introuvable: $id") }
        rule.enabled = enabled
        rule.updatedAt = LocalDateTime.now()
        return repo.save(rule)
    }

    /**
     * Evaluate a single rule against a dossier. Returns a detached
     * [ResultatValidation] (caller is responsible for association + persistence).
     * If the LLM is unavailable or misbehaves we surface AVERTISSEMENT rather
     * than crashing the whole validation pass.
     */
    fun evaluate(rule: CustomValidationRule, dossier: DossierPaiement): ResultatValidation {
        val applicable = when (dossier.type) {
            DossierType.BC -> rule.appliesToBC
            DossierType.CONTRACTUEL -> rule.appliesToContractuel
        }
        if (!applicable) {
            return ResultatValidation(
                dossier = dossier, regle = rule.code, libelle = rule.libelle,
                statut = StatutCheck.NON_APPLICABLE,
                detail = "Regle non applicable au type de dossier ${dossier.type}",
                source = "CUSTOM"
            )
        }

        if (!llm.isAvailable) {
            return ResultatValidation(
                dossier = dossier, regle = rule.code, libelle = rule.libelle,
                statut = StatutCheck.AVERTISSEMENT,
                detail = "Regle custom ignoree: cle API Claude non configuree",
                source = "CUSTOM"
            )
        }

        val targetTypes: Set<TypeDocument>? = rule.documentTypes
            ?.split(",")
            ?.mapNotNull { runCatching { TypeDocument.valueOf(it.trim()) }.getOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val docs = dossier.documents.filter { targetTypes == null || it.typeDocument in targetTypes }

        val requiredFields = rule.requiredFields
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val payload = buildDossierPayload(dossier, docs)
        val involvedIds = docs.mapNotNull { it.id?.toString() }

        return try {
            val raw = llm.callClaude(SYSTEM_PROMPT, buildUserPrompt(rule, payload, requiredFields))
            parseResponse(rule, dossier, raw, involvedIds)
        } catch (e: Exception) {
            log.warn("Custom rule {} failed for dossier {}: {}", rule.code, dossier.id, e.message)
            ResultatValidation(
                dossier = dossier, regle = rule.code, libelle = rule.libelle,
                statut = StatutCheck.AVERTISSEMENT,
                detail = "Evaluation IA indisponible: ${e.message?.take(300) ?: "erreur inconnue"}",
                source = "CUSTOM",
                documentIds = involvedIds.joinToString(",").ifBlank { null }
            )
        }
    }

    private fun validateRequest(req: CustomRuleRequest) {
        require(req.libelle.isNotBlank()) { "Le libelle est obligatoire" }
        require(req.prompt.isNotBlank()) { "Le prompt de la regle est obligatoire" }
        require(req.libelle.length <= 200) { "Libelle trop long (max 200)" }
        require(req.prompt.length <= 4000) { "Prompt trop long (max 4000 caracteres)" }
        require(req.appliesToBC || req.appliesToContractuel) {
            "La regle doit s'appliquer a au moins un type de dossier (BC ou Contractuel)"
        }
        require(req.severity in setOf("NON_CONFORME", "AVERTISSEMENT")) {
            "Severite invalide: ${req.severity}"
        }
    }

    private fun nextCode(): String {
        val existing = repo.findAllByOrderByCodeAsc()
            .mapNotNull { it.code.removePrefix("CUSTOM-").toIntOrNull() }
            .maxOrNull() ?: 0
        return "CUSTOM-%02d".format(existing + 1)
    }

    private fun buildDossierPayload(dossier: DossierPaiement, docs: List<Document>): Map<String, Any?> = mapOf(
        "dossier" to mapOf(
            "reference" to dossier.reference,
            "type" to dossier.type.name,
            "fournisseur" to dossier.fournisseur,
            "montantTtc" to dossier.montantTtc,
            "montantHt" to dossier.montantHt,
            "montantTva" to dossier.montantTva,
            "montantNetAPayer" to dossier.montantNetAPayer
        ),
        "documents" to docs.map { d ->
            mapOf(
                "id" to d.id?.toString(),
                "type" to d.typeDocument.name,
                "nomFichier" to d.nomFichier,
                "champsExtraits" to (d.donneesExtraites ?: emptyMap<String, Any?>())
            )
        }
    )

    private fun buildUserPrompt(rule: CustomValidationRule, payload: Map<String, Any?>, requiredFields: List<String>): String {
        val payloadJson = objectMapper.writeValueAsString(payload)
        val requiredBlock = if (requiredFields.isNotEmpty())
            "\nChamps requis signales par l'utilisateur: ${requiredFields.joinToString(", ")}.\n" +
            "Si l'un de ces champs est manquant dans les documents, reponds needsMoreInfo=true et liste les questions dans 'questions'.\n"
        else ""

        return """
REGLE A EVALUER: ${rule.libelle}
${if (!rule.description.isNullOrBlank()) "Description: ${rule.description}\n" else ""}
Critere (formule par l'utilisateur, a appliquer litteralement):
---
${rule.prompt}
---
$requiredBlock
Donnees extraites du dossier (JSON):
$payloadJson
        """.trimIndent()
    }

    private fun parseResponse(
        rule: CustomValidationRule,
        dossier: DossierPaiement,
        raw: String,
        involvedIds: List<String>
    ): ResultatValidation {
        val node: JsonNode = try {
            objectMapper.readTree(raw)
        } catch (e: Exception) {
            log.warn("Custom rule {} returned invalid JSON: {}", rule.code, raw.take(300))
            return ResultatValidation(
                dossier = dossier, regle = rule.code, libelle = rule.libelle,
                statut = StatutCheck.AVERTISSEMENT,
                detail = "Reponse IA non JSON: ${raw.take(200)}",
                source = "CUSTOM",
                documentIds = involvedIds.joinToString(",").ifBlank { null }
            )
        }

        val needsMoreInfo = node.path("needsMoreInfo").asBoolean(false)
        val questions = node.path("questions").takeIf { it.isArray }?.mapNotNull { it.asText(null) } ?: emptyList()
        val detailIa = node.path("detail").asText(null)?.takeIf { it.isNotBlank() }

        if (needsMoreInfo) {
            val q = if (questions.isEmpty()) "Informations manquantes dans les documents."
                    else "Informations manquantes: " + questions.joinToString(" | ")
            return ResultatValidation(
                dossier = dossier, regle = rule.code, libelle = rule.libelle,
                statut = StatutCheck.NON_APPLICABLE,
                detail = detailIa?.let { "$it — $q" } ?: q,
                source = "CUSTOM",
                documentIds = involvedIds.joinToString(",").ifBlank { null }
            )
        }

        val statutRaw = node.path("statut").asText("AVERTISSEMENT").uppercase()
        var statut = runCatching { StatutCheck.valueOf(statutRaw) }.getOrDefault(StatutCheck.AVERTISSEMENT)
        // User-chosen severity caps NON_CONFORME — if they asked for AVERTISSEMENT only,
        // downgrade IA-reported NON_CONFORME to stay aligned with their intent.
        if (statut == StatutCheck.NON_CONFORME && rule.severity == "AVERTISSEMENT") {
            statut = StatutCheck.AVERTISSEMENT
        }

        val evidences = node.path("evidences").takeIf { it.isArray }?.mapNotNull { ev ->
            val champ = ev.path("champ").asText(null) ?: return@mapNotNull null
            ValidationEvidence(
                role = ev.path("role").asText("trouve").ifBlank { "trouve" },
                champ = champ,
                libelle = ev.path("libelle").asText(null)?.takeIf { it.isNotBlank() },
                documentId = ev.path("documentId").asText(null)?.takeIf { it.isNotBlank() },
                documentType = ev.path("documentType").asText(null)?.takeIf { it.isNotBlank() },
                valeur = ev.path("valeur").asText(null)?.takeIf { it.isNotBlank() }
            )
        }?.takeIf { it.isNotEmpty() }

        return ResultatValidation(
            dossier = dossier, regle = rule.code, libelle = rule.libelle,
            statut = statut,
            detail = detailIa,
            source = "CUSTOM",
            evidences = evidences,
            documentIds = involvedIds.joinToString(",").ifBlank { null }
        )
    }

    companion object {
        private val SYSTEM_PROMPT = """
Tu es un controleur financier specialise dans la reconciliation de dossiers de paiement au Maroc (TVA, ICE, IF, RIB).
On te fournit une regle de controle definie par l'utilisateur et les donnees extraites d'un dossier (facture, BC, contrat, OP, attestations...).
Ta mission: evaluer la regle contre les donnees et retourner UNIQUEMENT un JSON valide, sans texte autour, de la forme exacte:
{
  "statut": "CONFORME" | "NON_CONFORME" | "AVERTISSEMENT" | "NON_APPLICABLE",
  "detail": "explication courte (<= 300 caracteres) factuelle, citant les valeurs observees",
  "evidences": [
    { "role": "attendu"|"trouve"|"source"|"calcule", "champ": "nomChamp",
      "libelle": "libelle humain", "documentId": "uuid", "documentType": "FACTURE",
      "valeur": "stringifiee" }
  ],
  "needsMoreInfo": false,
  "questions": []
}

REGLES STRICTES:
- Si tu manques d'informations pour juger, mets "needsMoreInfo": true et liste les champs manquants dans "questions".
- "statut" = CONFORME si la regle est respectee, NON_CONFORME si elle est violee, AVERTISSEMENT si doute raisonnable, NON_APPLICABLE si la regle ne s'applique pas aux documents presents.
- N'invente pas de valeurs. Appuie-toi uniquement sur les donnees fournies.
- Les montants sont en MAD sauf mention contraire. Les dates sont au format ISO.
- Ta reponse DOIT etre un JSON parsable. Pas de markdown, pas de prefixe, pas de suffixe.
        """.trimIndent()
    }
}

data class CustomRuleRequest(
    val libelle: String,
    val description: String? = null,
    val prompt: String,
    val enabled: Boolean = true,
    val appliesToBC: Boolean = true,
    val appliesToContractuel: Boolean = true,
    val documentTypes: List<String>? = null,
    val severity: String = "NON_CONFORME",
    val requiredFields: List<String>? = null
)
