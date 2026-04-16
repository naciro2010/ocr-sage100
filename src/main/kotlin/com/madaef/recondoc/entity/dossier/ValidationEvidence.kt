package com.madaef.recondoc.entity.dossier

/**
 * Structured evidence attached to a validation result.
 * Each evidence records WHICH field on WHICH document was read and WHAT role
 * it played in the comparison (attendu/trouve/source). Drives the inline UX:
 * click a value to open the source document, edit inline, see what rule reads.
 */
data class ValidationEvidence(
    val role: String,             // "attendu" | "trouve" | "source" | "calcule"
    val champ: String,            // ex: "montantTTC", "ice", "dateFacture"
    val libelle: String?,         // human label ex: "Montant TTC de la facture"
    val documentId: String?,      // UUID of source document
    val documentType: String?,    // ex: "FACTURE", "BON_COMMANDE"
    val valeur: String?,          // stringified value
    val page: Int? = null         // 1-based page hint for viewer (optional)
)
