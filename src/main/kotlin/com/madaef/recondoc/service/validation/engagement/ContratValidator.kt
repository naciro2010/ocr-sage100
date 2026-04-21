package com.madaef.recondoc.service.validation.engagement

import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.ResultatValidation
import com.madaef.recondoc.entity.dossier.StatutCheck
import com.madaef.recondoc.entity.engagement.EngagementContrat
import com.madaef.recondoc.entity.engagement.PeriodiciteContrat
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Regles R-C01..R-C05 : controles specifiques Contrat recurrent.
 *
 * Typologie : maintenance, assurance, abonnement, prestations periodiques.
 * Le paiement suit l'echeancier defini par la periodicite contractuelle.
 */
@Service
class ContratValidator : BaseEngagementValidator<EngagementContrat>() {

    override fun supports(): Class<EngagementContrat> = EngagementContrat::class.java

    override fun rules(): List<String> = listOf("R-C01", "R-C02", "R-C03", "R-C04", "R-C05")

    override fun validate(
        engagement: EngagementContrat,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): List<ResultatValidation> = listOf(
        ruleC01(engagement, dossier, context),
        ruleC02(engagement, dossier),
        ruleC03(engagement, dossier, context),
        ruleC04(engagement, dossier, context),
        ruleC05(engagement, dossier, context)
    )

    /** R-C01 : intervalle entre dossiers conforme a la periodicite. */
    private fun ruleC01(
        engagement: EngagementContrat,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): ResultatValidation {
        val periodicite = engagement.periodicite
            ?: return na("R-C01", "Periodicite respectee", dossier, "Pas de periodicite definie")

        val dateFacture = dossier.factures.firstOrNull()?.dateFacture
            ?: return na("R-C01", "Periodicite respectee", dossier, "Date facture manquante")

        val dossiersPrecedents = context.dossiersRattaches
            .filter { it.id != dossier.id }
            .mapNotNull { it.factures.firstOrNull()?.dateFacture }
            .filter { it < dateFacture }
            .sortedDescending()

        if (dossiersPrecedents.isEmpty()) {
            return ResultatValidation(
                dossier = dossier, regle = "R-C01",
                libelle = "Periodicite respectee ($periodicite)",
                statut = StatutCheck.CONFORME,
                detail = "Premier dossier du contrat, pas d'echeance precedente",
                source = SOURCE
            )
        }

        val datePrec = dossiersPrecedents.first()
        val joursEcart = ChronoUnit.DAYS.between(datePrec, dateFacture)
        val joursAttendus = joursAttendus(periodicite)
        val tolerance = (joursAttendus * 0.2).toLong().coerceAtLeast(3L)

        val statut = when {
            joursEcart in (joursAttendus - tolerance)..(joursAttendus + tolerance) -> StatutCheck.CONFORME
            joursEcart < joursAttendus - tolerance -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }

        return ResultatValidation(
            dossier = dossier, regle = "R-C01",
            libelle = "Periodicite respectee ($periodicite)",
            statut = statut,
            detail = "Ecart: ${joursEcart}j / Attendu: ${joursAttendus}j ± ${tolerance}j (prec: $datePrec)",
            valeurAttendue = "${joursAttendus}j ± ${tolerance}j",
            valeurTrouvee = "${joursEcart}j",
            source = SOURCE
        )
    }

    /** R-C02 : pas de paiement apres dateFin sauf reconduction tacite active. */
    private fun ruleC02(engagement: EngagementContrat, dossier: DossierPaiement): ResultatValidation {
        val dateFin = engagement.dateFin
            ?: return na("R-C02", "Paiement dans duree contrat", dossier, "Pas de date de fin definie")
        val dateFacture = dossier.factures.firstOrNull()?.dateFacture
            ?: return na("R-C02", "Paiement dans duree contrat", dossier, "Date facture manquante")

        val apresFin = dateFacture.isAfter(dateFin)
        val statut = when {
            !apresFin -> StatutCheck.CONFORME
            engagement.reconductionTacite -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }
        val detail = when {
            !apresFin -> "Facture du $dateFacture dans la duree contrat (fin: $dateFin)"
            engagement.reconductionTacite -> "Facture apres fin ($dateFin) ; reconduction tacite activee"
            else -> "Facture du $dateFacture apres fin contrat $dateFin sans reconduction"
        }
        return ResultatValidation(
            dossier = dossier, regle = "R-C02",
            libelle = "Paiement dans duree contrat",
            statut = statut,
            detail = detail,
            valeurAttendue = "≤ $dateFin",
            valeurTrouvee = dateFacture.toString(),
            source = SOURCE
        )
    }

    /** R-C03 : nombre de dossiers <= (duree / periodicite). */
    private fun ruleC03(
        engagement: EngagementContrat,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): ResultatValidation {
        val periodicite = engagement.periodicite
        val debut = engagement.dateDebut
        val fin = engagement.dateFin
        if (periodicite == null || debut == null || fin == null) {
            return na("R-C03", "Nombre de paiements ≤ duree/periodicite", dossier,
                "Periodicite, date debut ou date fin manquante")
        }

        val nbEcheancesMax = nbEcheancesEntre(debut, fin, periodicite)
        val nbDossiers = context.dossiersRattaches.size

        val statut = when {
            nbDossiers <= nbEcheancesMax -> StatutCheck.CONFORME
            nbDossiers <= nbEcheancesMax + 1 -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }
        return ResultatValidation(
            dossier = dossier, regle = "R-C03",
            libelle = "Nombre de paiements conforme a l'echeancier",
            statut = statut,
            detail = "Dossiers rattaches: $nbDossiers / Echeances prevues: $nbEcheancesMax (periodicite: $periodicite)",
            valeurAttendue = "≤ $nbEcheancesMax",
            valeurTrouvee = nbDossiers.toString(),
            source = SOURCE
        )
    }

    /** R-C04 : revision tarifaire respecte l'indice contractuel. */
    private fun ruleC04(
        engagement: EngagementContrat,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): ResultatValidation {
        val indice = engagement.indiceRevision?.trim()?.takeIf { it.isNotEmpty() }
            ?: return na("R-C04", "Revision tarifaire respectee", dossier,
                "Pas d'indice de revision defini")

        val montantActuel = dossier.factures.firstOrNull()?.montantTtc
            ?: return na("R-C04", "Revision tarifaire respectee", dossier, "Montant facture manquant")

        val dossiersPrecedents = context.dossiersRattaches
            .filter { it.id != dossier.id && (it.dateCreation < dossier.dateCreation) }
            .sortedByDescending { it.dateCreation }
        val montantPrecedent = dossiersPrecedents.firstOrNull()
            ?.factures?.firstOrNull()?.montantTtc

        if (montantPrecedent == null) {
            return ResultatValidation(
                dossier = dossier, regle = "R-C04",
                libelle = "Revision tarifaire respectee (indice $indice)",
                statut = StatutCheck.NON_APPLICABLE,
                detail = "Premier paiement, pas de reference pour calculer la revision",
                source = SOURCE
            )
        }

        val ecartPct = montantActuel.subtract(montantPrecedent)
            .divide(montantPrecedent, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .setScale(2, RoundingMode.HALF_UP)

        // Seuil prudent : 10% d'augmentation -> AVERTISSEMENT, 20% -> NON_CONFORME
        val statut = when {
            ecartPct <= BigDecimal("10") -> StatutCheck.CONFORME
            ecartPct <= BigDecimal("20") -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }
        return ResultatValidation(
            dossier = dossier, regle = "R-C04",
            libelle = "Revision tarifaire respectee (indice $indice)",
            statut = statut,
            detail = "Variation depuis dernier paiement: $ecartPct% ($montantPrecedent -> $montantActuel)",
            valeurAttendue = "≤ 10% (indice $indice)",
            valeurTrouvee = "$ecartPct%",
            source = SOURCE
        )
    }

    /** R-C05 : montant facture proche du montant de reference (± 5%). */
    private fun ruleC05(
        engagement: EngagementContrat,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): ResultatValidation {
        val montantReference = engagement.montantTtc
        val montantFacture = dossier.factures.firstOrNull()?.montantTtc
        val periodicite = engagement.periodicite

        if (montantReference == null || montantFacture == null || periodicite == null) {
            return na("R-C05", "Montant facture coherent echeancier", dossier,
                "Montant reference, facture ou periodicite manquant")
        }

        val debut = engagement.dateDebut
        val fin = engagement.dateFin
        val montantUnitaire = if (debut != null && fin != null) {
            val nbEcheances = nbEcheancesEntre(debut, fin, periodicite).coerceAtLeast(1)
            montantReference.divide(BigDecimal(nbEcheances), 2, RoundingMode.HALF_UP)
        } else {
            montantReference
        }

        val ecart = montantFacture.subtract(montantUnitaire).abs()
        val tolerance = montantUnitaire.multiply(BigDecimal("0.05"))

        val statut = when {
            ecart <= tolerance -> StatutCheck.CONFORME
            ecart <= tolerance.multiply(BigDecimal("2")) -> StatutCheck.AVERTISSEMENT
            else -> StatutCheck.NON_CONFORME
        }
        return ResultatValidation(
            dossier = dossier, regle = "R-C05",
            libelle = "Montant facture coherent avec echeancier",
            statut = statut,
            detail = "Montant facture: $montantFacture / Montant unitaire attendu: $montantUnitaire (± 5%)",
            valeurAttendue = "$montantUnitaire ± 5%",
            valeurTrouvee = montantFacture.toPlainString(),
            source = SOURCE
        )
    }

    private fun joursAttendus(p: PeriodiciteContrat): Long = when (p) {
        PeriodiciteContrat.MENSUEL -> 30L
        PeriodiciteContrat.TRIMESTRIEL -> 91L
        PeriodiciteContrat.SEMESTRIEL -> 182L
        PeriodiciteContrat.ANNUEL -> 365L
    }

    private fun nbEcheancesEntre(debut: LocalDate, fin: LocalDate, p: PeriodiciteContrat): Int {
        val jours = ChronoUnit.DAYS.between(debut, fin).coerceAtLeast(0L)
        return (jours / joursAttendus(p)).toInt() + 1
    }

}
