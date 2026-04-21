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
) : EngagementValidator<Engagement> {

    override fun supports(): Class<Engagement> = Engagement::class.java

    override fun rules(): List<String> = listOf("R-E01", "R-E02", "R-E03", "R-E04", "R-E05")

    override fun validate(
        engagement: Engagement,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): List<ResultatValidation> {
        val results = mutableListOf<ResultatValidation>()

        results += ruleE01(engagement, dossier, context)
        results += ruleE02(engagement, dossier)
        results += ruleE03(engagement, dossier)
        results += ruleE04(engagement, dossier)
        results += ruleE05(engagement, dossier)

        return results
    }

    /** R-E01 : Σ(dossiers.montantTtc) ≤ engagement.montantTtc (tolerance 2%). */
    private fun ruleE01(
        engagement: Engagement,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): ResultatValidation {
        val plafond = engagement.montantTtc
        val consomme = context.montantConsomme

        return if (plafond == null) {
            ResultatValidation(
                dossier = dossier, regle = "R-E01",
                libelle = "Plafond engagement respecte",
                statut = StatutCheck.NON_APPLICABLE,
                detail = "Pas de montant TTC renseigne sur l'engagement",
                source = "ENGAGEMENT"
            )
        } else {
            val tolerance = plafond.multiply(BigDecimal("0.02"))
            val depassement = consomme.subtract(plafond)
            val statut = when {
                depassement <= BigDecimal.ZERO -> StatutCheck.CONFORME
                depassement <= tolerance -> StatutCheck.AVERTISSEMENT
                else -> StatutCheck.NON_CONFORME
            }
            ResultatValidation(
                dossier = dossier, regle = "R-E01",
                libelle = "Somme dossiers ≤ plafond engagement",
                statut = statut,
                detail = "Consomme: ${consomme.setScale(2, RoundingMode.HALF_UP)} / Plafond: ${plafond.setScale(2, RoundingMode.HALF_UP)} (depassement: ${depassement.setScale(2, RoundingMode.HALF_UP)})",
                valeurAttendue = plafond.toPlainString(),
                valeurTrouvee = consomme.toPlainString(),
                source = "ENGAGEMENT"
            )
        }
    }

    /** R-E02 : coherence fournisseur engagement ↔ dossier. */
    private fun ruleE02(engagement: Engagement, dossier: DossierPaiement): ResultatValidation {
        val engFournisseur = engagement.fournisseur?.trim()?.takeIf { it.isNotEmpty() }
        val dosFournisseur = dossier.fournisseur?.trim()?.takeIf { it.isNotEmpty() }

        if (engFournisseur == null || dosFournisseur == null) {
            return ResultatValidation(
                dossier = dossier, regle = "R-E02",
                libelle = "Coherence fournisseur engagement ↔ dossier",
                statut = StatutCheck.NON_APPLICABLE,
                detail = "Fournisseur manquant sur ${if (engFournisseur == null) "engagement" else "dossier"}",
                source = "ENGAGEMENT"
            )
        }

        // Utilise le service de matching canonique existant pour tolerer variantes orthographiques
        val canoniqueEngagement = engagement.fournisseurCanonique?.id
        val canoniqueDossier = dossier.factures.firstOrNull()?.fournisseurCanonique?.id

        val match = when {
            canoniqueEngagement != null && canoniqueDossier != null -> canoniqueEngagement == canoniqueDossier
            else -> fournisseurMatching.normalize(engFournisseur) == fournisseurMatching.normalize(dosFournisseur)
        }

        return ResultatValidation(
            dossier = dossier, regle = "R-E02",
            libelle = "Coherence fournisseur engagement ↔ dossier",
            statut = if (match) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
            detail = "Engagement: $engFournisseur / Dossier: $dosFournisseur",
            valeurAttendue = engFournisseur,
            valeurTrouvee = dosFournisseur,
            source = "ENGAGEMENT"
        )
    }

    /** R-E03 : engagement.statut = ACTIF au moment du paiement. */
    private fun ruleE03(engagement: Engagement, dossier: DossierPaiement): ResultatValidation {
        return ResultatValidation(
            dossier = dossier, regle = "R-E03",
            libelle = "Engagement actif au moment du paiement",
            statut = when (engagement.statut) {
                StatutEngagement.ACTIF -> StatutCheck.CONFORME
                StatutEngagement.SUSPENDU -> StatutCheck.AVERTISSEMENT
                StatutEngagement.CLOTURE -> StatutCheck.NON_CONFORME
            },
            detail = "Statut engagement: ${engagement.statut.name}",
            source = "ENGAGEMENT"
        )
    }

    /** R-E04 : reference engagement citee dans l'OP ou la facture du dossier. */
    private fun ruleE04(engagement: Engagement, dossier: DossierPaiement): ResultatValidation {
        val ref = engagement.reference.trim()
        val opRefs = listOfNotNull(
            dossier.ordrePaiement?.referenceBcOuContrat,
            dossier.ordrePaiement?.referenceFacture,
            dossier.ordrePaiement?.referenceSage
        ).filter { it.isNotBlank() }
        val factureRefs = dossier.factures.mapNotNull { it.referenceContrat?.takeIf { r -> r.isNotBlank() } }
        val documentsMentions = (opRefs + factureRefs).map { it.trim() }

        val cited = documentsMentions.any { normalizeRef(it).contains(normalizeRef(ref)) }

        return ResultatValidation(
            dossier = dossier, regle = "R-E04",
            libelle = "Reference engagement citee dans le dossier",
            statut = if (cited) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
            detail = if (cited) "Reference '$ref' trouvee dans le dossier"
                else "Reference '$ref' non trouvee (citations: ${documentsMentions.joinToString(", ").ifEmpty { "aucune" }})",
            valeurAttendue = ref,
            valeurTrouvee = documentsMentions.firstOrNull(),
            source = "ENGAGEMENT"
        )
    }

    /** R-E05 : aucun nouveau dossier ne peut etre attache a un engagement CLOTURE. */
    private fun ruleE05(engagement: Engagement, dossier: DossierPaiement): ResultatValidation {
        return ResultatValidation(
            dossier = dossier, regle = "R-E05",
            libelle = "Rattachement autorise (engagement non cloture)",
            statut = if (engagement.statut == StatutEngagement.CLOTURE) StatutCheck.NON_CONFORME else StatutCheck.CONFORME,
            detail = if (engagement.statut == StatutEngagement.CLOTURE)
                "Engagement CLOTURE : aucun dossier ne doit y etre rattache"
            else "Engagement ${engagement.statut.name} : rattachement autorise",
            source = "ENGAGEMENT"
        )
    }

    private fun normalizeRef(s: String): String =
        s.lowercase().replace(Regex("[\\s\\-_/.']+"), "")
}
