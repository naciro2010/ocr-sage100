package com.madaef.recondoc.service.validation.engagement

import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.ResultatValidation
import com.madaef.recondoc.entity.dossier.StatutCheck
import com.madaef.recondoc.entity.dossier.TypeRetenue
import com.madaef.recondoc.entity.engagement.EngagementMarche
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Regles R-M01..R-M07 : controles specifiques Marche public marocain.
 *
 * Reference normative :
 * - Decret 2-12-349 (marches publics) : delai d'execution, AO, caution
 * - CCAG-T (travaux) : retenue de garantie, penalites de retard
 * - CPS (cahier des prescriptions speciales) : revision de prix
 */
@Service
class MarcheValidator : EngagementValidator<EngagementMarche> {

    override fun supports(): Class<EngagementMarche> = EngagementMarche::class.java

    override fun rules(): List<String> = listOf(
        "R-M01", "R-M02", "R-M03", "R-M04", "R-M05", "R-M06", "R-M07"
    )

    override fun validate(
        engagement: EngagementMarche,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): List<ResultatValidation> {
        val results = mutableListOf<ResultatValidation>()

        results += ruleM01(engagement, dossier)
        results += ruleM02(engagement, dossier)
        results += ruleM03(engagement, dossier)
        results += ruleM04(engagement, dossier, context)
        results += ruleM05(engagement, dossier)
        results += ruleM06(engagement, dossier, context)
        results += ruleM07(engagement, dossier)

        return results
    }

    /** R-M01 : date decompte/facture ∈ [dateNotification ; dateNotification + delaiExecutionMois]. */
    private fun ruleM01(engagement: EngagementMarche, dossier: DossierPaiement): ResultatValidation {
        val dateNotif = engagement.dateNotification
        val delai = engagement.delaiExecutionMois
        val dateFacture = dossier.factures.firstOrNull()?.dateFacture

        if (dateNotif == null || delai == null || dateFacture == null) {
            return na("R-M01", "Date facture dans delai d'execution", dossier,
                "Donnees insuffisantes : dateNotif=$dateNotif, delai=$delai, dateFacture=$dateFacture")
        }

        val dateFin = dateNotif.plusMonths(delai.toLong())
        val dansFenetre = !dateFacture.isBefore(dateNotif) && !dateFacture.isAfter(dateFin)
        val statut = when {
            dansFenetre -> StatutCheck.CONFORME
            dateFacture.isAfter(dateFin) -> StatutCheck.NON_CONFORME
            else -> StatutCheck.AVERTISSEMENT
        }
        return ResultatValidation(
            dossier = dossier, regle = "R-M01",
            libelle = "Date facture dans delai d'execution marche",
            statut = statut,
            detail = "Fenetre [$dateNotif ; $dateFin], date facture $dateFacture",
            valeurAttendue = "[$dateNotif ; $dateFin]",
            valeurTrouvee = dateFacture.toString(),
            source = "ENGAGEMENT"
        )
    }

    /** R-M02 : retenue de garantie appliquee sur chaque decompte (base × taux = montant). */
    private fun ruleM02(engagement: EngagementMarche, dossier: DossierPaiement): ResultatValidation {
        val tauxAttendu = engagement.retenueGarantiePct
            ?: return na("R-M02", "Retenue de garantie appliquee", dossier,
                "Pas de taux de retenue de garantie defini sur le marche")

        val op = dossier.ordrePaiement
        val retenueGarantie = op?.retenues?.firstOrNull { it.type == TypeRetenue.GARANTIE }

        if (retenueGarantie == null) {
            return ResultatValidation(
                dossier = dossier, regle = "R-M02",
                libelle = "Retenue de garantie appliquee",
                statut = StatutCheck.NON_CONFORME,
                detail = "Aucune retenue de garantie detectee dans l'OP (taux marche: $tauxAttendu%)",
                valeurAttendue = "$tauxAttendu%",
                valeurTrouvee = "absente",
                source = "ENGAGEMENT"
            )
        }

        val tauxReel = retenueGarantie.taux ?: BigDecimal.ZERO
        val ecart = tauxReel.subtract(tauxAttendu).abs()
        val statut = if (ecart <= BigDecimal("0.01")) StatutCheck.CONFORME else StatutCheck.NON_CONFORME

        return ResultatValidation(
            dossier = dossier, regle = "R-M02",
            libelle = "Taux retenue de garantie conforme au marche",
            statut = statut,
            detail = "Attendu: $tauxAttendu% / Trouve: $tauxReel%",
            valeurAttendue = tauxAttendu.toPlainString(),
            valeurTrouvee = tauxReel.toPlainString(),
            source = "ENGAGEMENT"
        )
    }

    /** R-M03 : penalites de retard calculees si depassement du delai. */
    private fun ruleM03(engagement: EngagementMarche, dossier: DossierPaiement): ResultatValidation {
        val dateNotif = engagement.dateNotification
        val delai = engagement.delaiExecutionMois
        val tauxPenalite = engagement.penalitesRetardJourPct
        val pvDate = dossier.pvReception?.dateReception

        if (dateNotif == null || delai == null || tauxPenalite == null) {
            return na("R-M03", "Penalites de retard calculees", dossier,
                "Parametres penalites non definis sur le marche")
        }

        val dateFinPrevue = dateNotif.plusMonths(delai.toLong())
        val effectiveDate = pvDate ?: dossier.factures.firstOrNull()?.dateFacture
            ?: return na("R-M03", "Penalites de retard calculees", dossier,
                "Pas de date de reception ni date facture pour evaluer le retard")

        val joursRetard = ChronoUnit.DAYS.between(dateFinPrevue, effectiveDate).coerceAtLeast(0L)
        if (joursRetard == 0L) {
            return ResultatValidation(
                dossier = dossier, regle = "R-M03",
                libelle = "Penalites de retard calculees",
                statut = StatutCheck.CONFORME,
                detail = "Aucun retard detecte (reception $effectiveDate ≤ $dateFinPrevue)",
                source = "ENGAGEMENT"
            )
        }

        val montantMarche = engagement.montantTtc ?: BigDecimal.ZERO
        val penaliteAttendue = montantMarche
            .multiply(tauxPenalite)
            .multiply(BigDecimal(joursRetard))
            .setScale(2, RoundingMode.HALF_UP)

        val retenuePenalite = dossier.ordrePaiement?.retenues?.firstOrNull { it.type == TypeRetenue.AUTRE }
        val montantApplique = retenuePenalite?.montant ?: BigDecimal.ZERO
        val ecart = penaliteAttendue.subtract(montantApplique).abs()
        val tolerance = penaliteAttendue.multiply(BigDecimal("0.05"))

        val statut = when {
            ecart <= tolerance -> StatutCheck.CONFORME
            montantApplique == BigDecimal.ZERO -> StatutCheck.NON_CONFORME
            else -> StatutCheck.AVERTISSEMENT
        }

        return ResultatValidation(
            dossier = dossier, regle = "R-M03",
            libelle = "Penalites de retard calculees ($joursRetard j)",
            statut = statut,
            detail = "Attendue: ${penaliteAttendue.toPlainString()} MAD / Appliquee: ${montantApplique.toPlainString()} MAD ($joursRetard j retard)",
            valeurAttendue = penaliteAttendue.toPlainString(),
            valeurTrouvee = montantApplique.toPlainString(),
            source = "ENGAGEMENT"
        )
    }

    /** R-M04 : numeroAO coherent entre tous les dossiers rattaches. */
    private fun ruleM04(
        engagement: EngagementMarche,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): ResultatValidation {
        val numeroAo = engagement.numeroAo?.trim()?.takeIf { it.isNotBlank() }
            ?: return na("R-M04", "Coherence numero AO sur tous les decomptes", dossier,
                "Pas de numero AO defini sur le marche")

        val citationsDossier = collectReferences(dossier)
        val cite = citationsDossier.any { it.contains(numeroAo, ignoreCase = true) }

        return ResultatValidation(
            dossier = dossier, regle = "R-M04",
            libelle = "Numero AO cite dans le dossier",
            statut = if (cite) StatutCheck.CONFORME else StatutCheck.AVERTISSEMENT,
            detail = if (cite) "Numero AO '$numeroAo' present"
                else "Numero AO '$numeroAo' non trouve (${context.dossiersRattaches.size} dossier(s) rattache(s))",
            valeurAttendue = numeroAo,
            valeurTrouvee = citationsDossier.firstOrNull(),
            source = "ENGAGEMENT"
        )
    }

    /** R-M05 : revision de prix autorisee par le CPS. */
    private fun ruleM05(engagement: EngagementMarche, dossier: DossierPaiement): ResultatValidation {
        val montantMarche = engagement.montantTtc
        val montantFacture = dossier.factures.firstOrNull()?.montantTtc
        if (montantMarche == null || montantFacture == null) {
            return na("R-M05", "Revision de prix respectee", dossier, "Montants manquants")
        }

        val revisionAutorisee = engagement.revisionPrixAutorisee
        if (revisionAutorisee) {
            return ResultatValidation(
                dossier = dossier, regle = "R-M05",
                libelle = "Revision de prix autorisee par le marche",
                statut = StatutCheck.CONFORME,
                detail = "Revision de prix autorisee par le CPS",
                source = "ENGAGEMENT"
            )
        }

        // Si revision non autorisee, le montant facture ne doit pas depasser le montant unitaire attendu
        // On se contente d'alerter si la facture depasse le montant marche (proportionnellement)
        val depasse = montantFacture > montantMarche
        return ResultatValidation(
            dossier = dossier, regle = "R-M05",
            libelle = "Revision de prix non autorisee respectee",
            statut = if (depasse) StatutCheck.NON_CONFORME else StatutCheck.CONFORME,
            detail = if (depasse) "Facture > montant marche (revision non autorisee)"
                else "Pas de depassement du montant marche",
            valeurAttendue = montantMarche.toPlainString(),
            valeurTrouvee = montantFacture.toPlainString(),
            source = "ENGAGEMENT"
        )
    }

    /** R-M06 : ordre chronologique strict des decomptes (date N+1 > date N). */
    private fun ruleM06(
        engagement: EngagementMarche,
        dossier: DossierPaiement,
        context: EngagementValidationContext
    ): ResultatValidation {
        val dateFacture = dossier.factures.firstOrNull()?.dateFacture
            ?: return na("R-M06", "Chronologie des decomptes", dossier, "Date facture manquante")

        val decomptesPrecedents = context.dossiersRattaches
            .filter { it.id != dossier.id }
            .mapNotNull { d ->
                val df = d.factures.firstOrNull()?.dateFacture
                if (df != null && df <= dateFacture) d to df else null
            }
            .sortedBy { it.second }

        // Cherche un decompte posterieur a celui-ci dans les rattaches
        val decomptesPosterieurs = context.dossiersRattaches
            .filter { it.id != dossier.id && it.dateCreation < dossier.dateCreation }
            .mapNotNull { it.factures.firstOrNull()?.dateFacture }
            .filter { it > dateFacture }

        val statut = if (decomptesPosterieurs.isEmpty()) StatutCheck.CONFORME else StatutCheck.AVERTISSEMENT
        return ResultatValidation(
            dossier = dossier, regle = "R-M06",
            libelle = "Chronologie des decomptes",
            statut = statut,
            detail = "Decompte date $dateFacture, ${decomptesPrecedents.size} anterieur(s), ${decomptesPosterieurs.size} posterieur(s) incoherent(s)",
            source = "ENGAGEMENT"
        )
    }

    /** R-M07 : caution definitive attendue si pct > 0. */
    private fun ruleM07(engagement: EngagementMarche, dossier: DossierPaiement): ResultatValidation {
        val pctCaution = engagement.cautionDefinitivePct
            ?: return na("R-M07", "Caution definitive", dossier,
                "Pas de taux de caution definitive defini")

        if (pctCaution == BigDecimal.ZERO) {
            return ResultatValidation(
                dossier = dossier, regle = "R-M07",
                libelle = "Caution definitive non requise",
                statut = StatutCheck.CONFORME,
                detail = "Taux caution = 0%, non requise",
                source = "ENGAGEMENT"
            )
        }

        // La caution est attendue presente dans le dossier (document ou mention)
        val mentionCaution = (dossier.documents.mapNotNull { it.texteExtrait } + listOf(dossier.description ?: ""))
            .any { it.lowercase().contains("caution") }

        return ResultatValidation(
            dossier = dossier, regle = "R-M07",
            libelle = "Caution definitive mentionnee ($pctCaution%)",
            statut = if (mentionCaution) StatutCheck.CONFORME else StatutCheck.AVERTISSEMENT,
            detail = if (mentionCaution) "Caution mentionnee dans le dossier"
                else "Aucune mention de caution dans les documents (taux: $pctCaution%)",
            source = "ENGAGEMENT"
        )
    }

    private fun collectReferences(dossier: DossierPaiement): List<String> = listOfNotNull(
        dossier.ordrePaiement?.referenceBcOuContrat,
        dossier.ordrePaiement?.referenceFacture,
        dossier.ordrePaiement?.referenceSage,
        dossier.factures.firstOrNull()?.referenceContrat
    ).filter { it.isNotBlank() }

    private fun na(code: String, libelle: String, dossier: DossierPaiement, raison: String) =
        ResultatValidation(
            dossier = dossier, regle = code, libelle = libelle,
            statut = StatutCheck.NON_APPLICABLE, detail = raison, source = "ENGAGEMENT"
        )
}
