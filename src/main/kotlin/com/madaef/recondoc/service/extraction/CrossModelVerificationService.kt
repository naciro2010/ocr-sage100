package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.AppSettingsService
import com.madaef.recondoc.service.OcrConfusions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Cross-model verification (Sprint 3 #4 fiabilite). Re-extrait les
 * identifiants reglementaires (ICE/RIB/IF) via un AUTRE modele (Haiku 4.5
 * par defaut) et compare avec l'extraction principale (Sonnet 4.6).
 *
 * Empilement des protections sur ICE/RIB/IF :
 *   1. Run principal Sonnet T=0 (deterministe)
 *   2. IdentifierConsistencyService : Sonnet T=0.5 -> brise determinisme local
 *   3. CrossModelVerificationService : Haiku T=0 -> brise determinisme MODELE
 *   4. CoVe : Sonnet T=0.3 + prompt different -> verification independante
 *
 * Une hallucination stable a la fois sur Sonnet T=0, Sonnet T>0 ET Haiku
 * est extremement rare. L'accord 3-en-3 = signal de fiabilite tres fort.
 *
 * Strategie de strip :
 *   - Run principal != Haiku (apres normalisation OCR) -> strip + critical
 *     warning + flag de revue humaine
 *   - Run principal non-null + Haiku null -> warning de non-confirmation,
 *     valeur conservee (Haiku peut avoir mal lu sans que ce soit une
 *     hallucination du run principal)
 *   - Run principal null -> ne pas penaliser
 *
 * Coût : ~1500 tokens / dossier porteur d'ICE+RIB+IF, sur Haiku 4.5
 * (~$0.0001/dossier). Negligeable vs gain fiabilite.
 *
 * Skip volontairement :
 *   - kill switch ai.cross_model.enabled=false
 *   - types non porteurs d'identifiants critiques (PV, checklist...)
 *   - aucun identifiant non-null dans le run principal -> 0 appel
 *   - exception reseau Claude -> log + on garde le run principal
 */
@Service
class CrossModelVerificationService(
    private val llmService: LlmExtractionService,
    private val appSettings: AppSettingsService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class CrossModelResult(
        val cleanedData: Map<String, Any?>,
        val hasCriticalDiscrepancy: Boolean,
        val discrepancies: List<Discrepancy>
    )

    data class Discrepancy(
        val field: String,
        val mainModelValue: String?,
        val crossModelValue: String?,
        val reason: String
    )

    private val applicableTypes = setOf(
        TypeDocument.FACTURE,
        TypeDocument.ORDRE_PAIEMENT,
        TypeDocument.ATTESTATION_FISCALE
    )

    private data class CriticalIdentifier(
        val field: String,
        val critical: Boolean,
        val isDigits: Boolean
    )

    private val identifiersByType: Map<TypeDocument, List<CriticalIdentifier>> = mapOf(
        TypeDocument.FACTURE to listOf(
            CriticalIdentifier("ice", critical = true, isDigits = true),
            CriticalIdentifier("rib", critical = false, isDigits = true),
            CriticalIdentifier("identifiantFiscal", critical = false, isDigits = true)
        ),
        TypeDocument.ORDRE_PAIEMENT to listOf(
            CriticalIdentifier("rib", critical = true, isDigits = true)
        ),
        TypeDocument.ATTESTATION_FISCALE to listOf(
            CriticalIdentifier("ice", critical = true, isDigits = true),
            CriticalIdentifier("identifiantFiscal", critical = false, isDigits = true)
        )
    )

    fun verify(
        type: TypeDocument,
        mainExtractionData: Map<String, Any?>,
        rawText: String
    ): CrossModelResult {
        if (!appSettings.isCrossModelVerificationEnabled()) {
            return CrossModelResult(mainExtractionData, hasCriticalDiscrepancy = false, discrepancies = emptyList())
        }
        if (type !in applicableTypes) {
            return CrossModelResult(mainExtractionData, hasCriticalDiscrepancy = false, discrepancies = emptyList())
        }

        val identifiers = identifiersByType[type] ?: emptyList()
        // Skip si aucun identifiant non-null dans le run principal
        val anyExtracted = identifiers.any { id ->
            mainExtractionData.getFieldCaseInsensitive(id.field)?.toString()?.isNotBlank() == true
        }
        if (!anyExtracted) {
            return CrossModelResult(mainExtractionData, hasCriticalDiscrepancy = false, discrepancies = emptyList())
        }

        val crossModelData = try {
            invokeCrossModel(type, rawText)
        } catch (e: Exception) {
            log.warn("Cross-model verification failed for {}: {} -> keeping run principal", type, e.message)
            return CrossModelResult(mainExtractionData, hasCriticalDiscrepancy = false, discrepancies = emptyList())
        }

        return compareAndApply(type, mainExtractionData, crossModelData, identifiers)
    }

    private fun invokeCrossModel(type: TypeDocument, rawText: String): Map<String, Any?> {
        val schema = ExtractionSchemas.IDENTIFIER_VERIFICATION
        val systemPrompt = buildSystemPrompt(type)
        val userContent = """
            Re-extrais UNIQUEMENT les identifiants reglementaires marocains du document
            (ICE, RIB, identifiantFiscal). Pour chacun :
              - Met null si l'identifiant n'apparait pas clairement dans le texte.
              - JAMAIS inventer ni completer un chiffre manquant.
              - Normaliser les confusions OCR O/0 et l/1 avant validation.

            Texte OCR :
            <document_content>
            ${rawText.take(20_000)}
            </document_content>
        """.trimIndent()

        val response = llmService.callClaudeToolWithModel(
            systemPrompt = systemPrompt,
            userContent = userContent,
            toolName = schema.name,
            inputSchema = schema.inputSchema,
            modelOverride = appSettings.getCrossModelVerificationModel(),
            temperatureOverride = 0.0
        )
        return response.toolInput
    }

    private fun compareAndApply(
        type: TypeDocument,
        main: Map<String, Any?>,
        cross: Map<String, Any?>,
        identifiers: List<CriticalIdentifier>
    ): CrossModelResult {
        val cleaned = main.toMutableMap()
        val discrepancies = mutableListOf<Discrepancy>()
        var hasCritical = false

        for (id in identifiers) {
            val mainValue = main.getFieldCaseInsensitive(id.field)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val crossValue = cross.getFieldCaseInsensitive(id.field)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

            // Run principal null -> rien a comparer
            if (mainValue == null) continue

            when {
                crossValue == null -> {
                    // Non-confirmation : Haiku n'a pas trouve. Signal de doute mais
                    // pas de strip (Haiku peut avoir mal lu).
                    discrepancies += Discrepancy(
                        id.field, mainValue, null,
                        "cross-model (${appSettings.getCrossModelVerificationModel()}) n'a pas confirme l'identifiant"
                    )
                    if (id.critical) hasCritical = true
                    log.warn("Cross-model unconfirmed on {}.{}: main='{}', cross=null", type, id.field, mainValue)
                }
                !valuesMatch(mainValue, crossValue, id.isDigits) -> {
                    // Vraie divergence : strip critique.
                    discrepancies += Discrepancy(
                        id.field, mainValue, crossValue,
                        "cross-model (${appSettings.getCrossModelVerificationModel()}) renvoie une valeur differente"
                    )
                    cleaned[id.field] = null
                    if (id.critical) hasCritical = true
                    log.warn("Cross-model violation on {}.{}: main='{}', cross='{}'", type, id.field, mainValue, crossValue)
                }
                // Sinon : accord -> tout va bien.
            }
        }

        if (discrepancies.isNotEmpty()) {
            val existing = (main["_warnings"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val added = discrepancies.map { d ->
                if (d.crossModelValue == null) {
                    "Cross-model unconfirmed on ${d.field}: ${d.reason} (main='${d.mainModelValue}')"
                } else {
                    "Cross-model violation on ${d.field}: ${d.reason} (main='${d.mainModelValue}', cross='${d.crossModelValue}')"
                }
            }
            cleaned["_warnings"] = existing + added
        }

        return CrossModelResult(cleaned, hasCritical, discrepancies)
    }

    private fun valuesMatch(a: String, b: String, digitsOnly: Boolean): Boolean {
        if (digitsOnly) {
            // Normalisation OCR (O->0, l->1) puis garder uniquement les chiffres.
            val na = OcrConfusions.digitsOnlyWithConfusions(a)
            val nb = OcrConfusions.digitsOnlyWithConfusions(b)
            return na.isNotEmpty() && na == nb
        }
        return a.equals(b, ignoreCase = true)
    }

    private fun buildSystemPrompt(type: TypeDocument): String = """
        Tu es un extracteur d'identifiants reglementaires marocains pour un document
        de type ${type.name}. Ta seule mission est de retrouver dans le texte OCR :
          - ICE : EXACTEMENT 15 chiffres (ex: 001509176000008)
          - RIB : EXACTEMENT 24 chiffres (ex: 011810000000123456789012)
          - identifiantFiscal (IF) : 5 a 15 chiffres

        REGLES STRICTES anti-hallucination :
          1. Mets null si l'identifiant n'apparait pas clairement dans le texte.
          2. JAMAIS inventer ni completer un chiffre manquant ou illisible.
          3. Normaliser les confusions OCR O->0 et l->1 avant validation.
          4. Si le nombre de chiffres ne correspond pas exactement (ex: 14 chiffres
             pour un ICE qui en exige 15), met null + warning.
          5. Cite la phrase source dans _sourceQuotes si possible.

        Tu n'as PAS a re-extraire le reste du document, juste les 3 identifiants.
        Reponds via l'outil verify_critical_identifiers.
    """.trimIndent()
}
