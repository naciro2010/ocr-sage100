package com.madaef.recondoc.service.validation.engagement

import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.ResultatValidation
import com.madaef.recondoc.entity.dossier.StatutCheck
import com.madaef.recondoc.entity.dossier.TypeRetenue
import com.madaef.recondoc.entity.engagement.EngagementBonCommande
import com.madaef.recondoc.repository.engagement.EngagementBonCommandeRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Regles R-B01..R-B04 : controles specifiques Bon de commande cadre.
 *
 * Reference normative :
 * - Art. 88 decret 2-12-349 : seuil du bon de commande (200 000 MAD HT)
 * - Anti-fractionnement : interdiction de morceler une commande pour
 *   rester sous le seuil.
 */
@Service
class BonCommandeValidator(
    private val bcRepo: EngagementBonCommandeRepository
) : EngagementValidator<EngagementBonCommande> {

    override fun supports(): Class<EngagementBonCommande> = EngagementBonCommande::class.java

    override fun rules(): List<String> = listOf("R-B01", "R-B02", "R-B03", "R-B04")

    override fun validate(
        engagement: EngagementBonCommande,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): List<ResultatValidation> {
        val results = mutableListOf<ResultatValidation>()

        results += ruleB01(engagement, dossier)
        results += ruleB02(engagement, dossier)
        results += ruleB03(engagement, dossier, context)
        results += ruleB04(engagement, dossier)

        return results
    }

    /** R-B01 : date facture <= dateValiditeFin du BC. */
    private fun ruleB01(engagement: EngagementBonCommande, dossier: DossierPaiement): ResultatValidation {
        val dateFin = engagement.dateValiditeFin
            ?: return na("R-B01", "Date facture dans validite BC", dossier,
                "Pas de date de validite definie sur le BC")
        val dateFacture = dossier.factures.firstOrNull()?.dateFacture
            ?: return na("R-B01", "Date facture dans validite BC", dossier,
                "Date facture manquante")

        val ok = !dateFacture.isAfter(dateFin)
        return ResultatValidation(
            dossier = dossier, regle = "R-B01",
            libelle = "Date facture dans validite BC",
            statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
            detail = if (ok) "Facture du $dateFacture emise avant echeance $dateFin"
                else "Facture du $dateFacture apres expiration $dateFin",
            valeurAttendue = "≤ $dateFin",
            valeurTrouvee = dateFacture.toString(),
            source = "ENGAGEMENT"
        )
    }

    /**
     * R-B02 : anti-fractionnement.
     * Somme des BC du meme fournisseur sur 12 mois ≤ seuil legal.
     */
    private fun ruleB02(engagement: EngagementBonCommande, dossier: DossierPaiement): ResultatValidation {
        val seuil = engagement.seuilAntiFractionnement
            ?: return na("R-B02", "Anti-fractionnement 12 mois", dossier,
                "Pas de seuil defini sur le BC (art. 88 decret : 200 000 MAD HT)")
        val fournisseur = engagement.fournisseur?.trim()?.takeIf { it.isNotEmpty() }
            ?: return na("R-B02", "Anti-fractionnement 12 mois", dossier,
                "Fournisseur manquant sur le BC")

        val dateFrom = LocalDate.now().minusMonths(12)
        val cumul = bcRepo.sumMontantByFournisseurSince(fournisseur, dateFrom, engagement.id)
        val nouveauCumul = cumul.add(engagement.montantTtc ?: BigDecimal.ZERO)

        val statut = when {
            nouveauCumul <= seuil -> StatutCheck.CONFORME
            nouveauCumul <= seuil.multiply(BigDecimal("1.05")) -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }
        return ResultatValidation(
            dossier = dossier, regle = "R-B02",
            libelle = "Anti-fractionnement BC 12 mois",
            statut = statut,
            detail = "Cumul 12 mois fournisseur '$fournisseur' = ${nouveauCumul.setScale(2, RoundingMode.HALF_UP)} MAD (seuil: $seuil MAD)",
            valeurAttendue = "≤ $seuil",
            valeurTrouvee = nouveauCumul.toPlainString(),
            source = "ENGAGEMENT"
        )
    }

    /** R-B03 : un dossier = une livraison (alerte si plusieurs factures pour un meme BC). */
    private fun ruleB03(
        engagement: EngagementBonCommande,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): ResultatValidation {
        val nbFactures = dossier.factures.size
        val dossiersAutres = context.dossiersRattaches.filter { it.id != dossier.id }
        val totalFactures = nbFactures + dossiersAutres.sumOf { it.factures.size }

        val statut = when {
            nbFactures <= 1 -> StatutCheck.CONFORME
            nbFactures <= 2 -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }
        return ResultatValidation(
            dossier = dossier, regle = "R-B03",
            libelle = "Un dossier = une livraison",
            statut = statut,
            detail = "Ce dossier contient $nbFactures facture(s), total BC: $totalFactures facture(s) sur ${context.dossiersRattaches.size} dossier(s)",
            source = "ENGAGEMENT"
        )
    }

    /** R-B04 : pas de retenue de garantie attendue (reservee aux marches). */
    private fun ruleB04(engagement: EngagementBonCommande, dossier: DossierPaiement): ResultatValidation {
        val retenueGarantie = dossier.ordrePaiement?.retenues?.firstOrNull { it.type == TypeRetenue.GARANTIE }
        return if (retenueGarantie == null || (retenueGarantie.montant ?: BigDecimal.ZERO) == BigDecimal.ZERO) {
            ResultatValidation(
                dossier = dossier, regle = "R-B04",
                libelle = "Pas de retenue de garantie sur BC",
                statut = StatutCheck.CONFORME,
                detail = "Aucune retenue de garantie detectee (conforme au regime BC)",
                source = "ENGAGEMENT"
            )
        } else {
            ResultatValidation(
                dossier = dossier, regle = "R-B04",
                libelle = "Retenue de garantie incoherente avec BC",
                statut = StatutCheck.AVERTISSEMENT,
                detail = "Retenue de garantie detectee (${retenueGarantie.montant} MAD) : reservee aux marches publics",
                valeurAttendue = "0 (regime BC)",
                valeurTrouvee = retenueGarantie.montant?.toPlainString(),
                source = "ENGAGEMENT"
            )
        }
    }

    private fun na(code: String, libelle: String, dossier: DossierPaiement, raison: String) =
        ResultatValidation(
            dossier = dossier, regle = code, libelle = libelle,
            statut = StatutCheck.NON_APPLICABLE, detail = raison, source = "ENGAGEMENT"
        )
}
