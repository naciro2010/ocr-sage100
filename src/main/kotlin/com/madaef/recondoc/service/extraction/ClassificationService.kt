package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.entity.dossier.TypeDocument
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

        log.warn("Could not classify document, marking as INCONNU for manual review")
        return TypeDocument.INCONNU
    }

    private fun classifyByKeywords(text: String): TypeDocument? {
        val lower = text.lowercase()
        // Order matters: most specific patterns first, broadest last
        return when {
            // Checklists (very specific codes)
            lower.contains("ccf-en-04") || (lower.contains("autocontr") && lower.contains("check")) -> TypeDocument.CHECKLIST_AUTOCONTROLE
            lower.contains("ccf-en-01") || (lower.contains("liasse") && lower.contains("pieces")) || (lower.contains("check") && lower.contains("pieces justificatives")) -> TypeDocument.CHECKLIST_PIECES

            // Tableau de controle BEFORE PV_RECEPTION (TC contains "reception" in its points)
            (lower.contains("tableau de contr") || lower.contains("tableau de controle")) && (lower.contains("conforme") || lower.contains("observation")) -> TypeDocument.TABLEAU_CONTROLE

            // Ordre de paiement
            lower.contains("ordre de paiement") || lower.contains("synthese du controleur") || lower.contains("synth\u00e8se du contr\u00f4leur") -> TypeDocument.ORDRE_PAIEMENT

            // PV de reception (strict: must have "proces-verbal" or "pv de reception" explicitly)
            lower.contains("proc\u00e8s-verbal") || lower.contains("proces-verbal") || lower.contains("pv de reception") || lower.contains("pv de r\u00e9ception") -> TypeDocument.PV_RECEPTION

            // Attestation fiscale
            lower.contains("regularite fiscale") || lower.contains("r\u00e9gularit\u00e9 fiscale") || lower.contains("direction generale des impots") || lower.contains("direction g\u00e9n\u00e9rale des imp\u00f4ts") -> TypeDocument.ATTESTATION_FISCALE

            // Contrat/Avenant
            lower.contains("contrat") && (lower.contains("avenant") || lower.contains("convention")) -> TypeDocument.CONTRAT_AVENANT

            // Bon de commande
            lower.contains("bon de commande") || Regex("cf\\s*sie\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(text) -> TypeDocument.BON_COMMANDE

            // Formulaire fournisseur
            lower.contains("ouverture de compte") || lower.contains("formulaire fournisseur") -> TypeDocument.FORMULAIRE_FOURNISSEUR

            // Facture (last - broadest match)
            lower.contains("facture") && (lower.contains("montant") || lower.contains("ttc") || lower.contains("tva") || lower.contains("net a payer")) -> TypeDocument.FACTURE

            else -> null
        }
    }
}
