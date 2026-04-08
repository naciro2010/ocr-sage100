package com.ocrsage.service.extraction

import com.ocrsage.entity.dossier.TypeDocument
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ClassificationService(
    private val llmExtractionService: LlmExtractionService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val CLASSIFICATION_PROMPT = """
            Tu es un classificateur de documents financiers marocains MADAEF.
            Classifie ce document dans une des categories suivantes :
            FACTURE, BON_COMMANDE, CONTRAT_AVENANT, ORDRE_PAIEMENT,
            CHECKLIST_AUTOCONTROLE, CHECKLIST_PIECES, TABLEAU_CONTROLE,
            PV_RECEPTION, ATTESTATION_FISCALE, FORMULAIRE_FOURNISSEUR

            Indices :
            - FACTURE : contient "facture", montant HT/TTC/TVA, numero facture
            - BON_COMMANDE : contient "bon de commande", "CF SIE", reference BC
            - CONTRAT_AVENANT : contient "contrat", "avenant", articles contractuels
            - ORDRE_PAIEMENT : contient "ordre de paiement", "OP", synthese controleur financier
            - CHECKLIST_AUTOCONTROLE : contient "CCF-EN-04", autocontrole, points de verification coches
            - CHECKLIST_PIECES : contient "CCF-EN-01", pieces justificatives
            - TABLEAU_CONTROLE : contient "tableau de controle", observations "Conforme"/"NA"
            - PV_RECEPTION : contient "proces-verbal", "PV de reception", attestation service fait
            - ATTESTATION_FISCALE : contient "attestation de regularite fiscale", DGI
            - FORMULAIRE_FOURNISSEUR : contient "ouverture de compte", coordonnees bancaires fournisseur

            Reponds UNIQUEMENT avec le nom de la categorie, rien d'autre.
        """.trimIndent()
    }

    fun classify(rawText: String): TypeDocument {
        // Try keyword-based classification first (fast, no API call)
        val keywordResult = classifyByKeywords(rawText)
        if (keywordResult != null) {
            log.info("Document classified by keywords: {}", keywordResult)
            return keywordResult
        }

        // Fallback to LLM classification
        try {
            val result = llmExtractionService.callClaude(CLASSIFICATION_PROMPT, rawText)
            val cleaned = result.trim().uppercase().replace(" ", "_")
            val type = TypeDocument.entries.find { it.name == cleaned }
            if (type != null) {
                log.info("Document classified by LLM: {}", type)
                return type
            }
        } catch (e: Exception) {
            log.warn("LLM classification failed: {}", e.message)
        }

        log.warn("Could not classify document, defaulting to FACTURE")
        return TypeDocument.FACTURE
    }

    private fun classifyByKeywords(text: String): TypeDocument? {
        val lower = text.lowercase()
        return when {
            lower.contains("ccf-en-04") || (lower.contains("autocontr") && lower.contains("check")) -> TypeDocument.CHECKLIST_AUTOCONTROLE
            lower.contains("ccf-en-01") || lower.contains("pieces justificatives") -> TypeDocument.CHECKLIST_PIECES
            lower.contains("ordre de paiement") || lower.contains("synth\u00e8se du contr\u00f4leur") -> TypeDocument.ORDRE_PAIEMENT
            lower.contains("proc\u00e8s-verbal") || lower.contains("pv de r\u00e9ception") || lower.contains("service fait") -> TypeDocument.PV_RECEPTION
            lower.contains("attestation de r\u00e9gularit\u00e9 fiscale") || lower.contains("direction g\u00e9n\u00e9rale des imp\u00f4ts") -> TypeDocument.ATTESTATION_FISCALE
            lower.contains("tableau de contr\u00f4le") && lower.contains("conforme") -> TypeDocument.TABLEAU_CONTROLE
            lower.contains("contrat") && (lower.contains("avenant") || lower.contains("article")) -> TypeDocument.CONTRAT_AVENANT
            lower.contains("bon de commande") || Regex("cf\\s*sie\\d+", RegexOption.IGNORE_CASE).containsMatchIn(text) -> TypeDocument.BON_COMMANDE
            lower.contains("ouverture de compte") -> TypeDocument.FORMULAIRE_FOURNISSEUR
            lower.contains("facture") && (lower.contains("montant") || lower.contains("ttc") || lower.contains("tva")) -> TypeDocument.FACTURE
            else -> null
        }
    }
}
