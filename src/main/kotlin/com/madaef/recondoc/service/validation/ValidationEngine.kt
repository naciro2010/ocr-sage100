package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.ResultatValidationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ValidationEngine(
    private val resultatRepository: ResultatValidationRepository,
    @Value("\${app.tolerance-montant:0.05}") private val toleranceMontant: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun validate(dossier: DossierPaiement): List<ResultatValidation> {
        log.info("Running validation for dossier {}", dossier.reference)

        resultatRepository.deleteByDossierId(dossier.id!!)

        val results = mutableListOf<ResultatValidation>()
        val tol = java.math.BigDecimal(toleranceMontant)

        val facture = dossier.facture
        val bc = dossier.bonCommande
        val op = dossier.ordrePaiement
        val contrat = dossier.contratAvenant
        val checklist = dossier.checklistAutocontrole
        val tableau = dossier.tableauControle
        val pv = dossier.pvReception
        val arf = dossier.attestationFiscale

        // R01 — Concordance montant TTC Facture ↔ BC
        if (dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontant("R01", "Concordance montant TTC : Facture = BC",
                facture.montantTtc, bc.montantTtc, tol, dossier)
        }

        // R02 — Concordance montant HT Facture ↔ BC
        if (dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontant("R02", "Concordance montant HT : Facture = BC",
                facture.montantHt, bc.montantHt, tol, dossier)
        }

        // R03 — Concordance TVA Facture ↔ BC
        if (dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontant("R03", "Concordance TVA : Facture = BC",
                facture.montantTva, bc.montantTva, tol, dossier)
        }

        // R04 — Montant OP = TTC facture (sans retenues)
        if (facture != null && op != null && op.retenues.isEmpty()) {
            results += checkMontant("R04", "Montant OP = TTC facture (sans retenues)",
                op.montantOperation, facture.montantTtc, tol, dossier)
        }

        // R05 — Montant OP = TTC - retenues
        if (facture != null && op != null && op.retenues.isNotEmpty()) {
            val totalRetenues = op.retenues.mapNotNull { it.montant }.fold(java.math.BigDecimal.ZERO) { acc, m -> acc.add(m) }
            val attendu = facture.montantTtc?.subtract(totalRetenues)
            results += checkMontant("R05", "Montant OP = TTC - retenues",
                op.montantOperation, attendu, tol, dossier)
        }

        // R06 — Verification arithmetique des retenues
        if (op != null) {
            for (ret in op.retenues) {
                if (ret.base != null && ret.taux != null && ret.montant != null) {
                    val calcule = ret.base!!.multiply(ret.taux).divide(java.math.BigDecimal(100), 2, java.math.RoundingMode.HALF_UP)
                    val ok = (calcule.subtract(ret.montant).abs()) <= tol
                    results += ResultatValidation(
                        dossier = dossier, regle = "R06",
                        libelle = "Calcul retenue ${ret.type} : base x taux = montant",
                        statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                        detail = "${ret.base} x ${ret.taux}% = ${calcule} (trouve: ${ret.montant})",
                        valeurAttendue = calcule.toPlainString(), valeurTrouvee = ret.montant?.toPlainString()
                    )
                }
            }
        }

        // R07 — Reference facture citee dans l'OP
        if (facture != null && op != null) {
            val ok = matchReference(op.referenceFacture, facture.numeroFacture)
            results += ResultatValidation(
                dossier = dossier, regle = "R07",
                libelle = "Reference facture citee dans l'OP",
                statut = if (ok) StatutCheck.CONFORME else if (op.referenceFacture == null) StatutCheck.AVERTISSEMENT else StatutCheck.NON_CONFORME,
                valeurAttendue = facture.numeroFacture, valeurTrouvee = op.referenceFacture
            )
        }

        // R08 — Reference BC/Contrat citee dans l'OP
        if (op != null) {
            val refAttendue = bc?.reference ?: contrat?.referenceContrat
            if (refAttendue != null) {
                val ok = matchReference(op.referenceBcOuContrat, refAttendue)
                results += ResultatValidation(
                    dossier = dossier, regle = "R08",
                    libelle = "Reference BC/Contrat citee dans l'OP",
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                    valeurAttendue = refAttendue, valeurTrouvee = op.referenceBcOuContrat
                )
            }
        }

        // R09 — Coherence ICE
        if (facture != null) {
            val ices = listOfNotNull(facture.ice, arf?.ice).distinct()
            results += ResultatValidation(
                dossier = dossier, regle = "R09",
                libelle = "Coherence ICE fournisseur entre documents",
                statut = when {
                    ices.size <= 1 -> StatutCheck.CONFORME
                    ices.toSet().size == 1 -> StatutCheck.CONFORME
                    else -> StatutCheck.NON_CONFORME
                },
                detail = if (ices.size > 1) "ICE trouves: ${ices.joinToString(", ")}" else "ICE: ${ices.firstOrNull() ?: "non renseigne"}"
            )
        }

        // R10 — Coherence IF
        if (facture != null) {
            val ifs = listOfNotNull(facture.identifiantFiscal, arf?.identifiantFiscal).distinct()
            results += ResultatValidation(
                dossier = dossier, regle = "R10",
                libelle = "Coherence IF fournisseur entre documents",
                statut = if (ifs.toSet().size <= 1) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                detail = "IF trouves: ${ifs.joinToString(", ").ifEmpty { "non renseigne" }}"
            )
        }

        // R11 — Coherence RIB Facture ↔ OP
        if (facture != null && op != null) {
            val ribFact = facture.rib?.replace("\\s".toRegex(), "")
            val ribOp = op.rib?.replace("\\s".toRegex(), "")
            val ok = ribFact != null && ribOp != null && ribFact == ribOp
            results += ResultatValidation(
                dossier = dossier, regle = "R11",
                libelle = "Coherence RIB : Facture = OP",
                statut = when {
                    ribFact == null || ribOp == null -> StatutCheck.AVERTISSEMENT
                    ok -> StatutCheck.CONFORME
                    else -> StatutCheck.NON_CONFORME
                },
                valeurAttendue = ribFact, valeurTrouvee = ribOp
            )
        }

        // R14 — Coherence fournisseur entre documents
        run {
            val fournisseurs = listOfNotNull(
                dossier.fournisseur,
                facture?.fournisseur,
                bc?.fournisseur,
                op?.beneficiaire,
                tableau?.fournisseur,
                checklist?.prestataire
            ).map { it.trim().lowercase() }.distinct()
            if (fournisseurs.size > 1) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R14",
                    libelle = "Coherence fournisseur entre documents",
                    statut = StatutCheck.AVERTISSEMENT,
                    detail = "Fournisseurs differents: ${fournisseurs.joinToString(", ")}"
                )
            } else if (fournisseurs.isNotEmpty()) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R14",
                    libelle = "Coherence fournisseur entre documents",
                    statut = StatutCheck.CONFORME,
                    detail = "Fournisseur: ${fournisseurs.first()}"
                )
            }
        }

        // R12 — Checklist complete
        if (checklist != null) {
            val nonValides = checklist.points.filter { it.estValide == false }
            val indetermines = checklist.points.filter { it.estValide == null }
            results += ResultatValidation(
                dossier = dossier, regle = "R12",
                libelle = "Checklist autocontrole complete",
                statut = when {
                    nonValides.isEmpty() && indetermines.isEmpty() -> StatutCheck.CONFORME
                    nonValides.isNotEmpty() -> StatutCheck.AVERTISSEMENT
                    else -> StatutCheck.AVERTISSEMENT
                },
                detail = "${checklist.points.count { it.estValide == true }}/${checklist.points.size} points valides" +
                    if (nonValides.isNotEmpty()) ", ${nonValides.size} non valides" else ""
            )
        }

        // R13 — Tableau de controle complet
        if (tableau != null) {
            val nonConformes = tableau.points.filter {
                it.observation?.lowercase()?.contains("non conforme") == true
            }
            results += ResultatValidation(
                dossier = dossier, regle = "R13",
                libelle = "Tableau de controle financier complet",
                statut = if (nonConformes.isEmpty()) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                detail = if (nonConformes.isEmpty()) "Tous les points sont Conforme ou NA"
                    else "${nonConformes.size} point(s) Non conforme"
            )
        }

        // R17 — Coherence temporelle
        run {
            val dateBcContrat = bc?.dateBc ?: contrat?.dateSignature
            val dateFacture = facture?.dateFacture
            val dateOp = op?.dateEmission
            if (dateBcContrat != null && dateFacture != null) {
                val ok = !dateFacture.isBefore(dateBcContrat)
                results += ResultatValidation(
                    dossier = dossier, regle = "R17a",
                    libelle = "Chronologie : date BC/Contrat <= date Facture",
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.AVERTISSEMENT,
                    valeurAttendue = dateBcContrat.toString(), valeurTrouvee = dateFacture.toString()
                )
            }
            if (dateFacture != null && dateOp != null) {
                val ok = !dateOp.isBefore(dateFacture)
                results += ResultatValidation(
                    dossier = dossier, regle = "R17b",
                    libelle = "Chronologie : date Facture <= date OP",
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.AVERTISSEMENT,
                    valeurAttendue = dateFacture.toString(), valeurTrouvee = dateOp.toString()
                )
            }
        }

        // R18 — Validite attestation fiscale
        if (arf != null && arf.dateEdition != null) {
            val valide = arf.dateEdition!!.plusMonths(6).isAfter(java.time.LocalDate.now())
            results += ResultatValidation(
                dossier = dossier, regle = "R18",
                libelle = "Validite attestation fiscale (6 mois)",
                statut = if (valide) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                detail = "Editee le ${arf.dateEdition}, valide jusqu'au ${arf.dateEdition!!.plusMonths(6)}"
            )
        }

        // Save all results
        results.forEach { it.dateExecution = LocalDateTime.now() }
        resultatRepository.saveAll(results)

        val conformes = results.count { it.statut == StatutCheck.CONFORME }
        val nonConformes = results.count { it.statut == StatutCheck.NON_CONFORME }
        log.info("Validation complete for {}: {}/{} conforme, {} non-conforme",
            dossier.reference, conformes, results.size, nonConformes)

        return results
    }

    private fun checkMontant(
        regle: String, libelle: String,
        valeur1: java.math.BigDecimal?, valeur2: java.math.BigDecimal?,
        tolerance: java.math.BigDecimal, dossier: DossierPaiement
    ): ResultatValidation {
        if (valeur1 == null || valeur2 == null) {
            return ResultatValidation(
                dossier = dossier, regle = regle, libelle = libelle,
                statut = StatutCheck.AVERTISSEMENT,
                detail = "Valeur manquante",
                valeurAttendue = valeur2?.toPlainString(), valeurTrouvee = valeur1?.toPlainString()
            )
        }
        val diff = valeur1.subtract(valeur2).abs()
        val ok = diff <= tolerance
        return ResultatValidation(
            dossier = dossier, regle = regle, libelle = libelle,
            statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
            detail = "${valeur1.toPlainString()} vs ${valeur2.toPlainString()} (ecart: ${diff.toPlainString()})",
            valeurAttendue = valeur2.toPlainString(), valeurTrouvee = valeur1.toPlainString()
        )
    }

    private fun matchReference(ref1: String?, ref2: String?): Boolean {
        if (ref1 == null || ref2 == null) return false
        val normalize = { s: String -> s.replace("[\\s\\-_/.']+".toRegex(), "").trimStart('0').lowercase() }
        val n1 = normalize(ref1)
        val n2 = normalize(ref2)
        if (n1 == n2) return true
        // Only allow contains if the shorter string is at least 4 chars (avoid "001" matching everything)
        val shorter = if (n1.length < n2.length) n1 else n2
        val longer = if (n1.length < n2.length) n2 else n1
        return shorter.length >= 4 && longer.contains(shorter)
    }
}
