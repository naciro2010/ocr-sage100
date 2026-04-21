package com.madaef.recondoc.service.validation.engagement

import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.ResultatValidation
import com.madaef.recondoc.entity.dossier.StatutCheck
import com.madaef.recondoc.entity.engagement.Engagement
import com.madaef.recondoc.entity.engagement.StatutEngagement
import com.madaef.recondoc.service.fournisseur.FournisseurMatchingService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Regles R-E01..R-E05 : controles communs applicables quelque soit le type
 * d'engagement (Marche / BC cadre / Contrat).
 *
 * Toutes les regles sont sautees si le dossier n'est pas rattache a un
 * engagement (engagement == null) : garantie zero-regression sur les dossiers
 * historiques.
 */
@Service
class CommonEngagementValidator(
    private val fournisseurMatching: FournisseurMatchingService
) : BaseEngagementValidator<Engagement>() {

    override fun supports(): Class<Engagement> = Engagement::class.java

    override fun rules(): List<String> = listOf("R-E01", "R-E02", "R-E03", "R-E04", "R-E05")

    override fun validate(
        engagement: Engagement,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): List<ResultatValidation> = listOf(
        ruleE01(engagement, dossier, context),
        ruleE02(engagement, dossier),
        ruleE03(engagement, dossier),
        ruleE04(engagement, dossier),
        ruleE05(engagement, dossier)
    )

    /** R-E01 : Σ(dossiers.montantTtc) ≤ engagement.montantTtc (tolerance 2%). */
    private fun ruleE01(
        engagement: Engagement,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): ResultatValidation {
        val plafond = engagement.montantTtc
            ?: return na("R-E01", "Plafond engagement respecte", dossier,
                "Pas de montant TTC renseigne sur l'engagement")

        val consomme = context.montantConsomme
        val tolerance = plafond.multiply(BigDecimal("0.02"))
        val depassement = consomme.subtract(plafond)
        val statut = when {
            depassement <= BigDecimal.ZERO -> StatutCheck.CONFORME
            depassement <= tolerance -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }
        return result(
            "R-E01", "Somme dossiers ≤ plafond engagement", dossier, statut,
            detail = "Consomme: ${consomme.setScale(2, RoundingMode.HALF_UP)} / Plafond: ${plafond.setScale(2, RoundingMode.HALF_UP)} (depassement: ${depassement.setScale(2, RoundingMode.HALF_UP)})",
            attendu = plafond.toPlainString(),
            trouve = consomme.toPlainString()
        )
    }

    /** R-E02 : coherence fournisseur engagement ↔ dossier. */
    private fun ruleE02(engagement: Engagement, dossier: DossierPaiement): ResultatValidation {
        val engFournisseur = engagement.fournisseur?.trim()?.takeIf { it.isNotEmpty() }
        val dosFournisseur = dossier.fournisseur?.trim()?.takeIf { it.isNotEmpty() }

        if (engFournisseur == null || dosFournisseur == null) {
            return na("R-E02", "Coherence fournisseur engagement ↔ dossier", dossier,
                "Fournisseur manquant sur ${if (engFournisseur == null) "engagement" else "dossier"}")
        }

        val canoniqueEngagement = engagement.fournisseurCanonique?.id
        val canoniqueDossier = dossier.factures.firstOrNull()?.fournisseurCanonique?.id
        val match = when {
            canoniqueEngagement != null && canoniqueDossier != null -> canoniqueEngagement == canoniqueDossier
            else -> fournisseurMatching.normalize(engFournisseur) == fournisseurMatching.normalize(dosFournisseur)
        }

        return result(
            "R-E02", "Coherence fournisseur engagement ↔ dossier", dossier,
            if (match) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
            detail = "Engagement: $engFournisseur / Dossier: $dosFournisseur",
            attendu = engFournisseur, trouve = dosFournisseur
        )
    }

    /** R-E03 : engagement.statut = ACTIF au moment du paiement. */
    private fun ruleE03(engagement: Engagement, dossier: DossierPaiement): ResultatValidation = result(
        "R-E03", "Engagement actif au moment du paiement", dossier,
        when (engagement.statut) {
            StatutEngagement.ACTIF -> StatutCheck.CONFORME
            StatutEngagement.SUSPENDU -> StatutCheck.AVERTISSEMENT
            StatutEngagement.CLOTURE -> StatutCheck.NON_CONFORME
        },
        detail = "Statut engagement: ${engagement.statut.name}"
    )

    /** R-E04 : reference engagement citee dans l'OP ou la facture du dossier. */
    private fun ruleE04(engagement: Engagement, dossier: DossierPaiement): ResultatValidation {
        val ref = engagement.reference.trim()
        val mentions = collectReferences(dossier)
        val cited = mentions.any { normalizeRef(it).contains(normalizeRef(ref)) }

        return result(
            "R-E04", "Reference engagement citee dans le dossier", dossier,
            if (cited) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
            detail = if (cited) "Reference '$ref' trouvee dans le dossier"
                else "Reference '$ref' non trouvee (citations: ${mentions.joinToString(", ").ifEmpty { "aucune" }})",
            attendu = ref, trouve = mentions.firstOrNull()
        )
    }

    /** R-E05 : aucun nouveau dossier ne peut etre attache a un engagement CLOTURE. */
    private fun ruleE05(engagement: Engagement, dossier: DossierPaiement): ResultatValidation {
        val cloture = engagement.statut == StatutEngagement.CLOTURE
        return result(
            "R-E05", "Rattachement autorise (engagement non cloture)", dossier,
            if (cloture) StatutCheck.NON_CONFORME else StatutCheck.CONFORME,
            detail = if (cloture) "Engagement CLOTURE : aucun dossier ne doit y etre rattache"
                else "Engagement ${engagement.statut.name} : rattachement autorise"
        )
    }

    private fun collectReferences(dossier: DossierPaiement): List<String> = buildList {
        dossier.ordrePaiement?.let {
            add(it.referenceBcOuContrat)
            add(it.referenceFacture)
            add(it.referenceSage)
        }
        dossier.factures.forEach { add(it.referenceContrat) }
    }.filterNotNull().map { it.trim() }.filter { it.isNotBlank() }

    private fun normalizeRef(s: String): String =
        s.lowercase().replace(Regex("[\\s\\-_/.']+"), "")
}
