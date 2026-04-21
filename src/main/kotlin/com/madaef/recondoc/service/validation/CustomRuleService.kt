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
     * Evaluate several applicable rules against the same dossier in a SINGLE
     * Claude call. The dossier payload is serialised once and shared across
     * all rules, which divides the cost and latency by N compared to calling
     * [evaluate] in a loop. Falls back gracefully to per-rule evaluation if
     * the batch response is malformed or the LLM times out.
     */
    fun evaluateBatch(rules: List<CustomValidationRule>, dossier: DossierPaiement): List<ResultatValidation> {
        if (rules.isEmpty()) return emptyList()
        if (rules.size == 1) return listOf(evaluate(rules.first(), dossier))

        val (applicable, notApplicable) = rules.partition { it.isApplicableTo(dossier.type) }
        val skipped = notApplicable.map { nonApplicableResult(it, dossier, "CUSTOM_BATCH") }
        if (applicable.isEmpty()) return skipped

        if (!llm.isAvailable) {
            return skipped + applicable.map { missingKeyResult(it, dossier, "CUSTOM_BATCH") }
        }

        // Restrict the shared payload to document types actually referenced by at least
        // one rule in the batch — keeps the single-serialisation win while honouring
        // the per-rule `documentTypes` visibility that evaluate() applies individually.
        val neededTypes = applicable.flatMap { it.targetDocumentTypes() ?: emptyList() }.toSet()
        val docs = if (neededTypes.isEmpty()) dossier.documents.toList()
                   else dossier.documents.filter { it.typeDocument in neededTypes }
        val payload = buildDossierPayload(dossier, docs)
        val involvedIds = docs.mapNotNull { it.id?.toString() }

        return try {
            val raw = llm.callClaude(BATCH_SYSTEM_PROMPT, buildBatchUserPrompt(applicable, payload))
            val verdicts = parseBatchResponse(raw)
            val missing = applicable.filter { verdicts[it.code] == null }.map { it.code }
            if (missing.isNotEmpty()) {
                log.warn("Batch returned partial results: missing verdicts for {} (dossier={})", missing, dossier.id)
            }
            val results = applicable.map { rule ->
                verdicts[rule.code]?.let { buildResultFromVerdict(rule, dossier, it, involvedIds) }
                    ?: ResultatValidation(
                        dossier = dossier, regle = rule.code, libelle = rule.libelle,
                        statut = StatutCheck.AVERTISSEMENT,
                        detail = "Verdict manquant dans la reponse IA groupee. Relancer ou corriger manuellement.",
                        source = "CUSTOM_BATCH",
                        documentIds = involvedIds.joinToString(",").ifBlank { null }
                    )
            }
            skipped + results
        } catch (e: Exception) {
            log.warn("Batch custom-rule evaluation failed ({}): falling back to per-rule calls", e.message)
            skipped + applicable.map { evaluate(it, dossier) }
        }
    }

    private fun CustomValidationRule.isApplicableTo(type: DossierType): Boolean = when (type) {
        DossierType.BC -> appliesToBC
        DossierType.CONTRACTUEL -> appliesToContractuel
    }

    private fun CustomValidationRule.targetDocumentTypes(): Set<TypeDocument>? = documentTypes
        ?.split(",")
        ?.mapNotNull { runCatching { TypeDocument.valueOf(it.trim()) }.getOrNull() }
        ?.toSet()
        ?.takeIf { it.isNotEmpty() }

    private fun nonApplicableResult(rule: CustomValidationRule, dossier: DossierPaiement, source: String) =
        ResultatValidation(
            dossier = dossier, regle = rule.code, libelle = rule.libelle,
            statut = StatutCheck.NON_APPLICABLE,
            detail = "Regle non applicable au type de dossier ${dossier.type}",
            source = source
        )

    private fun missingKeyResult(rule: CustomValidationRule, dossier: DossierPaiement, source: String) =
        ResultatValidation(
            dossier = dossier, regle = rule.code, libelle = rule.libelle,
            statut = StatutCheck.AVERTISSEMENT,
            detail = "Regle custom ignoree: cle API Claude non configuree",
            source = source
        )

    /**
     * Evaluate a single rule against a dossier. Returns a detached
     * [ResultatValidation] (caller is responsible for association + persistence).
     * If the LLM is unavailable or misbehaves we surface AVERTISSEMENT rather
     * than crashing the whole validation pass.
     */
    fun evaluate(rule: CustomValidationRule, dossier: DossierPaiement): ResultatValidation {
        if (!rule.isApplicableTo(dossier.type)) return nonApplicableResult(rule, dossier, "CUSTOM")
        if (!llm.isAvailable) return missingKeyResult(rule, dossier, "CUSTOM")

        val targetTypes = rule.targetDocumentTypes()
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
            return ResultatValidation(
                dossier = dossier, regle = rule.code, libelle = rule.libelle,
                statut = StatutCheck.NON_APPLICABLE,
                detail = needsMoreInfoDetail(detailIa, questions),
                source = "CUSTOM",
                documentIds = involvedIds.joinToString(",").ifBlank { null }
            )
        }

        return ResultatValidation(
            dossier = dossier, regle = rule.code, libelle = rule.libelle,
            statut = parseStatut(node.path("statut"), rule),
            detail = detailIa,
            source = "CUSTOM",
            evidences = parseEvidences(node.path("evidences")),
            documentIds = involvedIds.joinToString(",").ifBlank { null }
        )
    }

    private fun parseEvidences(arrNode: JsonNode): List<ValidationEvidence>? {
        if (!arrNode.isArray) return null
        return arrNode.mapNotNull { ev ->
            val champ = ev.path("champ").asText(null) ?: return@mapNotNull null
            ValidationEvidence(
                role = ev.path("role").asText("trouve").ifBlank { "trouve" },
                champ = champ,
                libelle = ev.path("libelle").asText(null)?.takeIf { it.isNotBlank() },
                documentId = ev.path("documentId").asText(null)?.takeIf { it.isNotBlank() },
                documentType = ev.path("documentType").asText(null)?.takeIf { it.isNotBlank() },
                valeur = ev.path("valeur").asText(null)?.takeIf { it.isNotBlank() }
            )
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * User-chosen severity caps NON_CONFORME — if the rule only wants AVERTISSEMENT,
     * we downgrade any IA-reported NON_CONFORME to stay aligned with their intent.
     */
    private fun parseStatut(statutNode: JsonNode, rule: CustomValidationRule): StatutCheck {
        val raw = statutNode.asText("AVERTISSEMENT").uppercase()
        val parsed = runCatching { StatutCheck.valueOf(raw) }.getOrDefault(StatutCheck.AVERTISSEMENT)
        return if (parsed == StatutCheck.NON_CONFORME && rule.severity == "AVERTISSEMENT")
            StatutCheck.AVERTISSEMENT else parsed
    }

    private fun needsMoreInfoDetail(detailIa: String?, questions: List<String>): String {
        val q = if (questions.isEmpty()) "Informations manquantes dans les documents."
                else "Informations manquantes: " + questions.joinToString(" | ")
        return detailIa?.let { "$it — $q" } ?: q
    }

    // ---------------- Batch helpers ----------------

    private data class BatchVerdict(
        val statutNode: JsonNode,
        val detail: String?,
        val evidences: List<ValidationEvidence>?,
        val needsMoreInfo: Boolean,
        val questions: List<String>,
        val documentIds: List<String>?
    )

    private fun buildBatchUserPrompt(rules: List<CustomValidationRule>, payload: Map<String, Any?>): String {
        val rulesBlock = rules.joinToString("\n\n") { rule ->
            val required = rule.requiredFields
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val reqLine = if (required.isEmpty()) "" else
                "Champs requis: ${required.joinToString(", ")}. Si l'un manque, mets needsMoreInfo=true et liste dans questions.\n"
            """
[REGLE ${rule.code}] ${rule.libelle}
${if (!rule.description.isNullOrBlank()) "Description: ${rule.description}\n" else ""}Severite demandee: ${rule.severity}
Applicabilite: BC=${rule.appliesToBC}, CONTRACTUEL=${rule.appliesToContractuel}
$reqLine
Critere (a appliquer litteralement):
${rule.prompt}
            """.trimIndent()
        }
        val payloadJson = objectMapper.writeValueAsString(payload)
        return """
Voici ${rules.size} regles a evaluer CONTRE LE MEME dossier. Reponds UNIQUEMENT par un JSON
de la forme { "verdicts": [ { "code": "CUSTOM-01", ... }, ... ] } avec une entree par regle,
codes cites ci-dessous. Pas de texte autour. Pas de markdown.

REGLES:
$rulesBlock

DONNEES DU DOSSIER (JSON):
$payloadJson
        """.trimIndent()
    }

    private fun parseBatchResponse(raw: String): Map<String, BatchVerdict> {
        val root = objectMapper.readTree(raw)
        val arr = root.path("verdicts").takeIf { it.isArray } ?: return emptyMap()
        val out = mutableMapOf<String, BatchVerdict>()
        for (node in arr) {
            val code = node.path("code").asText(null)?.takeIf { it.isNotBlank() } ?: continue
            val docIds = node.path("documentIds").takeIf { it.isArray }
                ?.mapNotNull { it.asText(null)?.takeIf { s -> s.isNotBlank() } }
            out[code] = BatchVerdict(
                statutNode = node.path("statut"),
                detail = node.path("detail").asText(null)?.takeIf { it.isNotBlank() },
                evidences = parseEvidences(node.path("evidences")),
                needsMoreInfo = node.path("needsMoreInfo").asBoolean(false),
                questions = node.path("questions").takeIf { it.isArray }
                    ?.mapNotNull { it.asText(null)?.takeIf { s -> s.isNotBlank() } } ?: emptyList(),
                documentIds = docIds
            )
        }
        return out
    }

    private fun buildResultFromVerdict(
        rule: CustomValidationRule,
        dossier: DossierPaiement,
        v: BatchVerdict,
        fallbackDocIds: List<String>
    ): ResultatValidation {
        val docIdsCsv = (v.documentIds ?: fallbackDocIds).joinToString(",").ifBlank { null }
        if (v.needsMoreInfo) {
            return ResultatValidation(
                dossier = dossier, regle = rule.code, libelle = rule.libelle,
                statut = StatutCheck.NON_APPLICABLE,
                detail = needsMoreInfoDetail(v.detail, v.questions),
                source = "CUSTOM_BATCH",
                documentIds = docIdsCsv
            )
        }
        return ResultatValidation(
            dossier = dossier, regle = rule.code, libelle = rule.libelle,
            statut = parseStatut(v.statutNode, rule),
            detail = v.detail,
            source = "CUSTOM_BATCH",
            evidences = v.evidences,
            documentIds = (v.documentIds ?: fallbackDocIds).joinToString(",").ifBlank { null }
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

        private val BATCH_SYSTEM_PROMPT = """
Tu es un controleur financier specialise dans la reconciliation de dossiers de paiement au Maroc (TVA, ICE, IF, RIB).
On te fournit UN dossier (facture, BC, contrat, OP, attestations...) et PLUSIEURS regles personnalisees a evaluer.
Ta mission: evaluer chaque regle INDEPENDAMMENT contre les memes donnees et retourner UNIQUEMENT un JSON valide de la forme:
{
  "verdicts": [
    {
      "code": "CUSTOM-XX",
      "statut": "CONFORME" | "NON_CONFORME" | "AVERTISSEMENT" | "NON_APPLICABLE",
      "detail": "explication courte (<= 300 caracteres) factuelle, citant les valeurs observees",
      "evidences": [
        { "role": "attendu"|"trouve"|"source"|"calcule", "champ": "nomChamp",
          "libelle": "libelle humain", "documentId": "uuid", "documentType": "FACTURE",
          "valeur": "stringifiee" }
      ],
      "documentIds": ["uuid", "uuid"],
      "needsMoreInfo": false,
      "questions": []
    }
  ]
}

REGLES STRICTES:
- Un verdict PAR regle listee, code exact (CUSTOM-XX).
- Evalue chaque regle separement ; ne deduis pas une regle d'une autre.
- Si tu manques d'informations pour juger, mets needsMoreInfo=true et liste les champs manquants dans questions.
- N'invente aucune valeur. Appuie-toi uniquement sur le dossier fourni.
- Les montants sont en MAD sauf mention contraire. Les dates sont au format ISO.
- Ta reponse DOIT etre un JSON parsable, PAS de markdown, PAS de prefixe ni suffixe.
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
