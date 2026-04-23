package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.TypeDocument
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

data class QualityReport(
    val score: Int,
    val confidenceOcr: Double,
    val confidenceExtraction: Double,
    val completudeObligatoires: Double,
    val completudeImportants: Double,
    val coherenceArithmetique: Double,
    val missingMandatory: List<String>,
    val missingImportant: List<String>
)

@Service
class ExtractionQualityService {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Les cles listees ici DOIVENT correspondre exactement aux cles emises
        // par les prompts de ExtractionPrompts.kt. Tout desalignement produit
        // des faux "missing" qui declenchent des re-extractions inutiles.
        private val MANDATORY: Map<TypeDocument, List<String>> = mapOf(
            TypeDocument.FACTURE to listOf("numeroFacture", "dateFacture", "montantTTC", "fournisseur", "ice"),
            TypeDocument.BON_COMMANDE to listOf("reference", "dateBc", "montantTTC", "fournisseur"),
            TypeDocument.ORDRE_PAIEMENT to listOf("numeroOp", "dateEmission", "montantOperation", "rib", "beneficiaire"),
            TypeDocument.CONTRAT_AVENANT to listOf("referenceContrat", "dateSignature", "parties", "objet"),
            TypeDocument.ATTESTATION_FISCALE to listOf("numero", "dateEdition", "raisonSociale", "ice"),
            TypeDocument.PV_RECEPTION to listOf("dateReception", "referenceContrat", "prestations"),
            TypeDocument.CHECKLIST_AUTOCONTROLE to listOf("points", "referenceFacture", "prestataire"),
            TypeDocument.CHECKLIST_PIECES to listOf("pieces", "referenceFacture"),
            TypeDocument.TABLEAU_CONTROLE to listOf("points", "referenceFacture", "fournisseur"),
            TypeDocument.MARCHE to listOf("reference", "objet", "fournisseur", "montantTtc", "dateDocument"),
            TypeDocument.BON_COMMANDE_CADRE to listOf("reference", "objet", "fournisseur", "montantTtc", "dateDocument"),
            TypeDocument.CONTRAT_CADRE to listOf("reference", "objet", "fournisseur", "montantTtc", "dateDocument")
        )

        private val IMPORTANT: Map<TypeDocument, List<String>> = mapOf(
            TypeDocument.FACTURE to listOf("montantHT", "tauxTVA", "lignes", "identifiantFiscal", "rib"),
            TypeDocument.BON_COMMANDE to listOf("montantHT", "tauxTVA", "lignes", "objet", "signataire"),
            TypeDocument.ORDRE_PAIEMENT to listOf("referenceFacture", "retenues", "banque", "syntheseControleur"),
            TypeDocument.CONTRAT_AVENANT to listOf("grillesTarifaires", "dateEffet", "numeroAvenant"),
            TypeDocument.ATTESTATION_FISCALE to listOf("identifiantFiscal", "rc", "estEnRegle", "codeVerification"),
            TypeDocument.PV_RECEPTION to listOf("signataireMadaef", "signataireFournisseur", "periodeDebut", "periodeFin"),
            TypeDocument.CHECKLIST_AUTOCONTROLE to listOf("signataires", "dateEtablissement", "referenceBc", "nomProjet"),
            TypeDocument.CHECKLIST_PIECES to listOf("typeDossier", "signataire", "dateEtablissement"),
            TypeDocument.TABLEAU_CONTROLE to listOf("signataire", "dateControle", "conclusionGenerale", "societeGeree"),
            TypeDocument.MARCHE to listOf(
                "montantHt", "tauxTva", "numeroAo", "dateAo", "categorie", "delaiExecutionMois",
                "retenueGarantiePct", "cautionDefinitivePct", "revisionPrixAutorisee"
            ),
            TypeDocument.BON_COMMANDE_CADRE to listOf(
                "montantHt", "tauxTva", "plafondMontant", "dateValiditeFin", "seuilAntiFractionnement"
            ),
            TypeDocument.CONTRAT_CADRE to listOf(
                "montantHt", "tauxTva", "dateDebut", "dateFin", "periodicite",
                "reconductionTacite", "preavisResiliationJours"
            )
        )

        private const val COHERENCE_TOLERANCE = 0.01
    }

    fun evaluate(document: Document): QualityReport {
        val type = document.typeDocument
        val data = document.donneesExtraites ?: emptyMap()

        val mandatory = MANDATORY[type] ?: emptyList()
        val important = IMPORTANT[type] ?: emptyList()

        val missingMandatory = mandatory.filter { !isFieldPresent(data, it) }
        val missingImportant = important.filter { !isFieldPresent(data, it) }

        val completudeObligatoires = if (mandatory.isEmpty()) 1.0
            else (mandatory.size - missingMandatory.size).toDouble() / mandatory.size
        val completudeImportants = if (important.isEmpty()) 1.0
            else (important.size - missingImportant.size).toDouble() / important.size

        val coherence = computeCoherenceScore(type, data)
        val confidenceOcr = document.ocrConfidence.takeIf { it >= 0 }?.coerceIn(0.0, 100.0)?.div(100.0) ?: 0.5
        val rawConfidenceExtraction = document.extractionConfidence.takeIf { it >= 0 }?.coerceIn(0.0, 1.0) ?: 0.5

        // Anti auto-tromperie : Claude a tendance a declarer `_confidence >= 0.9`
        // meme quand plusieurs champs obligatoires sont null ou quand l'arithmetique
        // HT+TVA=TTC ne tombe pas juste. Une confidence haute avec >=2 champs
        // obligatoires manquants OU une coherence <0.7 est un signal fort d'auto-
        // validation abusive : on la plafonne a 0.5 pour que le score composite
        // reflete la realite et que la re-extraction auto se declenche.
        val autoTromperie = rawConfidenceExtraction > 0.8 && (missingMandatory.size >= 2 || coherence < 0.7)
        val confidenceExtraction = if (autoTromperie) {
            log.warn("Penalizing self-declared _confidence={} on document (type={}): {} missing mandatory, coherence={}",
                rawConfidenceExtraction, type, missingMandatory.size, coherence)
            minOf(rawConfidenceExtraction, 0.5)
        } else rawConfidenceExtraction

        val completude = 0.70 * completudeObligatoires + 0.30 * completudeImportants
        val raw = 0.30 * confidenceOcr +
                  0.30 * confidenceExtraction +
                  0.25 * completude +
                  0.15 * coherence
        val score = (raw * 100.0).coerceIn(0.0, 100.0).toInt()

        return QualityReport(
            score = score,
            confidenceOcr = confidenceOcr,
            confidenceExtraction = confidenceExtraction,
            completudeObligatoires = completudeObligatoires,
            completudeImportants = completudeImportants,
            coherenceArithmetique = coherence,
            missingMandatory = missingMandatory,
            missingImportant = missingImportant
        )
    }

    fun applyTo(document: Document): QualityReport {
        val report = evaluate(document)
        document.extractionQualityScore = report.score
        document.missingMandatoryFields = report.missingMandatory.joinToString(",").ifBlank { null }
        log.info("Quality score for document {} (type={}): {} (missing={})",
            document.id, document.typeDocument, report.score, report.missingMandatory)
        return report
    }

    private fun isFieldPresent(data: Map<String, Any?>, field: String): Boolean {
        val v = data.getFieldCaseInsensitive(field) ?: return false
        return when (v) {
            is String -> v.isNotBlank()
            is Collection<*> -> v.isNotEmpty()
            is Map<*, *> -> v.isNotEmpty()
            else -> true
        }
    }

    private fun computeCoherenceScore(type: TypeDocument, data: Map<String, Any?>): Double {
        return when (type) {
            TypeDocument.FACTURE -> combineCoherence(
                htTvaTtcCoherence(data),
                sommeLignesVsTotal(data, "montantTotalHT", "montantHT")
            )
            TypeDocument.BON_COMMANDE -> combineCoherence(
                htTvaTtcCoherence(data),
                sommeLignesVsTotal(data, "montantLigneHT", "montantHT")
            )
            else -> 1.0
        }
    }

    /**
     * Coherence HT + TVA ≈ TTC (tolerance 1%). Retourne null si l'un des trois
     * champs est absent (pas de penalite dans ce cas, la completude s'en charge).
     */
    private fun htTvaTtcCoherence(data: Map<String, Any?>): Double? {
        val ht = numericField(data, "montantHT")
        val tva = numericField(data, "montantTVA")
        val ttc = numericField(data, "montantTTC")
        if (ht == null || tva == null || ttc == null || ttc == BigDecimal.ZERO) return null
        val expected = ht.add(tva)
        val diff = expected.subtract(ttc).abs()
        val ratio = diff.divide(ttc.abs().max(BigDecimal.ONE), 6, RoundingMode.HALF_UP).toDouble()
        return coherenceFromRatio(ratio)
    }

    /**
     * Coherence somme(lignes.<lineAmountKey>) ≈ <totalHtKey>. Bloque les
     * extractions ou Claude a pris un total correct mais a mal lu une ligne
     * (ou l'inverse), ce qui casse R01/R03 (concordance montants facture/BC)
     * et fait passer des faux negatifs critiques. Retourne null si le document
     * n'a pas de lignes ou pas de total HT -> pas de penalite injustifiee.
     */
    private fun sommeLignesVsTotal(
        data: Map<String, Any?>,
        lineAmountKey: String,
        totalHtKey: String
    ): Double? {
        val total = numericField(data, totalHtKey) ?: return null
        if (total.signum() == 0) return null
        val lignes = data.getFieldCaseInsensitive("lignes") as? List<*> ?: return null
        if (lignes.isEmpty()) return null
        val sommeBd = lignes
            .mapNotNull { line ->
                @Suppress("UNCHECKED_CAST")
                (line as? Map<String, Any?>)?.let { numericField(it, lineAmountKey) }
            }
            .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
        // Aucune ligne avec montant exploitable : on ne force pas de coherence.
        if (sommeBd.signum() == 0) return null
        val diff = sommeBd.subtract(total).abs()
        val ratio = diff.divide(total.abs().max(BigDecimal.ONE), 6, RoundingMode.HALF_UP).toDouble()
        return coherenceFromRatio(ratio)
    }

    private fun coherenceFromRatio(ratio: Double): Double =
        if (ratio <= COHERENCE_TOLERANCE) 1.0 else (1.0 - (ratio * 5.0)).coerceAtLeast(0.0)

    /**
     * Combine plusieurs signaux de coherence (HT+TVA=TTC, somme lignes=HT...) :
     * moyenne arithmetique des signaux disponibles, avec un plancher 0.5 si
     * aucun signal n'a pu etre evalue (equivalent a l'ancien comportement).
     */
    private fun combineCoherence(vararg signals: Double?): Double {
        val present = signals.filterNotNull()
        if (present.isEmpty()) return 0.5
        return present.average()
    }

    private fun numericField(data: Map<String, Any?>, key: String): BigDecimal? {
        val v = data.getFieldCaseInsensitive(key) ?: return null
        return when (v) {
            is Number -> BigDecimal(v.toString())
            is String -> v.replace("[^\\d.,\\-]".toRegex(), "").let { s ->
                if (s.isEmpty()) return null
                val lc = s.lastIndexOf(','); val ld = s.lastIndexOf('.')
                if (lc > ld) s.replace(".", "").replace(",", ".").toBigDecimalOrNull()
                else s.replace(",", "").toBigDecimalOrNull()
            }
            else -> null
        }
    }
}
