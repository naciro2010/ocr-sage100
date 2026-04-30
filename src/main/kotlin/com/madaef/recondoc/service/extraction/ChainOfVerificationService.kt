package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.AppSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Chain-of-Verification (Sprint 2 #2 fiabilite) : second appel Claude,
 * independant du run principal, qui ne fait QUE valider que les valeurs
 * deja extraites pour les champs critiques apparaissent exactement dans
 * le texte OCR. Defense en profondeur :
 *
 *   Run 1 : extraction + citations source_quote (T=0)
 *           -> GroundingValidator strip si citation introuvable
 *           -> IdentifierConsistencyService re-extrait ICE/RIB/IF a T>0
 *   Run 2 : verification batch (CoVe, ce service, T=0.3)
 *           -> Claude regarde sa PROPRE proposition avec un prompt different
 *           -> 1 seul appel pour tous les champs (batch) -> coût ~2k tokens
 *
 * Strategie batch : un seul appel avec une liste {field, value}. Le schema
 * tool_use force la sortie {field, presentInSource, citation, reason}.
 * Si presentInSource=false OU citation absente du texte OCR, on strip le
 * champ + warning.
 *
 * Skip volontairement :
 *   - les types sans champs critiques (PV, checklist) : pas d'appel
 *   - les dossiers ou tous les champs critiques sont deja null : 0 appel
 *   - les exceptions reseau/timeout : on log + on garde l'extraction du
 *     run 1 (les couches precedentes ont deja fait leur travail)
 *
 * Activable / desactivable via `ai.cove.enabled` (defaut true).
 */
@Service
class ChainOfVerificationService(
    private val llmService: LlmExtractionService,
    private val appSettings: AppSettingsService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class VerificationResult(
        val cleanedData: Map<String, Any?>,
        val unverified: List<UnverifiedField>
    )

    data class UnverifiedField(val field: String, val value: String, val reason: String)

    /**
     * Champs critiques a verifier par type de document. Doit rester aligne
     * avec GroundingValidator.checks et ExtractionPrompts._sourceQuotes :
     * meme perimetre = pas d'angle mort entre les couches.
     */
    private val criticalFields: Map<TypeDocument, List<String>> = mapOf(
        TypeDocument.FACTURE to listOf("ice", "rib", "identifiantFiscal", "numeroFacture", "montantTTC", "dateFacture", "fournisseur"),
        TypeDocument.BON_COMMANDE to listOf("reference", "fournisseur", "montantTTC", "dateBc"),
        TypeDocument.ORDRE_PAIEMENT to listOf("numeroOp", "rib", "beneficiaire", "montantOperation", "dateEmission"),
        TypeDocument.CONTRAT_AVENANT to listOf("referenceContrat", "dateSignature", "objet"),
        TypeDocument.ATTESTATION_FISCALE to listOf("numero", "raisonSociale", "ice", "identifiantFiscal", "dateEdition"),
        TypeDocument.MARCHE to listOf("reference", "fournisseur", "montantTtc", "dateDocument"),
        TypeDocument.BON_COMMANDE_CADRE to listOf("reference", "fournisseur", "montantTtc", "dateDocument"),
        TypeDocument.CONTRAT_CADRE to listOf("reference", "fournisseur", "montantTtc", "dateDocument")
    )

    fun verify(
        type: TypeDocument,
        extractedData: Map<String, Any?>,
        rawText: String
    ): VerificationResult {
        if (!appSettings.isChainOfVerificationEnabled()) {
            return VerificationResult(extractedData, emptyList())
        }
        val fields = criticalFields[type] ?: return VerificationResult(extractedData, emptyList())

        // Construire la liste {field, value} des champs presents (non-null,
        // non-vide). Si tous les champs critiques sont absents, pas d'appel
        // a faire — ne pas brûler de tokens pour rien.
        val toVerify = fields.mapNotNull { f ->
            val v = extractedData.getFieldCaseInsensitive(f)?.toString()?.trim()
            if (!v.isNullOrEmpty()) f to v else null
        }
        if (toVerify.isEmpty()) {
            return VerificationResult(extractedData, emptyList())
        }

        val verifications = try {
            invokeClaudeBatch(type, toVerify, rawText)
        } catch (e: Exception) {
            log.warn("CoVe verification failed for {} ({} fields): {} -> keeping run 1 extraction",
                type, toVerify.size, e.message)
            return VerificationResult(extractedData, emptyList())
        }

        return applyVerdicts(extractedData, verifications, rawText)
    }

    /**
     * Appel Claude batch : un seul tool_use call avec la liste des champs
     * a verifier. Temperature legere (0.3) pour briser le determinisme du
     * run 1 sans deriver completement.
     */
    private fun invokeClaudeBatch(
        type: TypeDocument,
        toVerify: List<Pair<String, String>>,
        rawText: String
    ): List<VerifyEntry> {
        val systemPrompt = buildSystemPrompt(type)
        val userContent = buildUserContent(toVerify, rawText)
        val schema = ExtractionSchemas.VERIFICATION_BATCH

        val response = llmService.callClaudeToolWithTemperature(
            systemPrompt = systemPrompt,
            userContent = userContent,
            toolName = schema.name,
            inputSchema = schema.inputSchema,
            kind = CallKind.EXTRACTION,
            temperatureOverride = 0.3
        )
        @Suppress("UNCHECKED_CAST")
        val rawList = response.toolInput["verifications"] as? List<Map<String, Any?>>
            ?: return emptyList()
        return rawList.mapNotNull { raw ->
            val field = raw["field"]?.toString() ?: return@mapNotNull null
            val present = raw["presentInSource"] as? Boolean ?: return@mapNotNull null
            val citation = raw["citation"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val reason = raw["reason"]?.toString()?.trim().orEmpty()
            VerifyEntry(field, present, citation, reason)
        }
    }

    private fun applyVerdicts(
        data: Map<String, Any?>,
        verifications: List<VerifyEntry>,
        rawText: String
    ): VerificationResult {
        val cleaned = data.toMutableMap()
        val unverified = mutableListOf<UnverifiedField>()
        val normalizedHaystack = normalizeForQuote(rawText)

        for (verify in verifications) {
            val current = cleaned.getFieldCaseInsensitive(verify.field)?.toString() ?: continue
            if (current.isBlank()) continue

            val accept = if (verify.presentInSource) {
                // Citation fournie ? La verifier dans le texte OCR. Sans citation,
                // on accepte par defaut (le run principal a ses propres garde-fous)
                // mais on flag.
                if (verify.citation != null) {
                    val normalizedCitation = normalizeForQuote(verify.citation)
                    val citationMatches = normalizedCitation.length < 5 ||
                        normalizedHaystack.contains(normalizedCitation)
                    if (!citationMatches) {
                        log.warn("CoVe: field '{}' value='{}' marked present but citation '{}' absent from OCR text",
                            verify.field, current, verify.citation.take(60))
                        false
                    } else true
                } else {
                    log.debug("CoVe: field '{}' marked present without citation -> accepted (no penalty)", verify.field)
                    true
                }
            } else false

            if (!accept) {
                cleaned[verify.field] = null
                unverified += UnverifiedField(verify.field, current, verify.reason.ifBlank { "non verifie par CoVe" })
                log.warn("CoVe: stripping field '{}' (value='{}', reason='{}')",
                    verify.field, current, verify.reason)
            }
        }

        if (unverified.isNotEmpty()) {
            val existing = (data["_warnings"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val added = unverified.map {
                "Chain-of-Verification: champ '${it.field}' non verifie (valeur='${it.value}', motif='${it.reason}')"
            }
            cleaned["_warnings"] = existing + added
        }

        return VerificationResult(cleaned, unverified)
    }

    private fun buildSystemPrompt(type: TypeDocument): String = """
        Tu es un verificateur de second tour pour des extractions automatiques de documents
        marocains (MADAEF / Groupe CDG). Document de type ${type.name}.

        Pour CHAQUE entree {field, value} fournie, ta seule mission est de dire si la
        valeur proposee apparait EXACTEMENT dans le texte OCR du document, et fournir
        une citation textuelle de preuve.

        REGLES STRICTES :
          1. JAMAIS dire presentInSource=true par sympathie pour le 1er extracteur.
             Si tu n'es pas sur a >= 95%, dire false.
          2. La citation doit etre une sous-chaine continue du texte OCR (preserver
             espaces, ponctuation, separateurs OCR), pas une paraphrase.
          3. Si la valeur est partielle (RIB tronque, ICE incomplet) -> false.
          4. Si la valeur a ete completee/devine -> false.
          5. Si plusieurs occurrences existent, citer celle qui correspond au champ
             demande (en-tete pour les identifiants, recapitulatif pour les totaux).

        Tu n'as PAS a re-extraire, juste a verifier. Reponds via l'outil
        verify_extracted_values_batch avec UNE entree par champ recu.
    """.trimIndent()

    private fun buildUserContent(toVerify: List<Pair<String, String>>, rawText: String): String {
        val list = toVerify.joinToString("\n") { (f, v) -> "  - field: $f, value: \"$v\"" }
        return """
            Champs a verifier :
            $list

            Texte OCR du document :
            <document_content>
            ${rawText.take(20_000)}
            </document_content>

            Pour chaque champ, retourne via l'outil :
              - field : nom EXACT du champ (copie depuis la liste)
              - presentInSource : true/false (true UNIQUEMENT si presence exacte)
              - citation : phrase exacte du texte OCR si present (null sinon)
              - reason : court motif (<200 chars)
        """.trimIndent()
    }

    private fun normalizeForQuote(s: String): String =
        s.lowercase().replace(Regex("[\\s\\-.,/:;'\"()\\[\\]]+"), "")

    private data class VerifyEntry(
        val field: String,
        val presentInSource: Boolean,
        val citation: String?,
        val reason: String
    )
}
