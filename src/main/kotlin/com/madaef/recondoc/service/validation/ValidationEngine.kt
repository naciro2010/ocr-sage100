package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.ResultatValidationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Service
class ValidationEngine(
    private val resultatRepository: ResultatValidationRepository,
    @Value("\${app.tolerance-montant:0.05}") private val toleranceMontant: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val WHITESPACE_RE = "\\s".toRegex()
        private val REF_NORMALIZE_RE = "[\\s\\-_/.']+".toRegex()
        private val MONTH_NAMES = listOf(
            "janvier", "fevrier", "mars", "avril", "mai", "juin",
            "juillet", "aout", "septembre", "octobre", "novembre", "decembre"
        )
    }

    @Transactional
    fun validate(dossier: DossierPaiement): List<ResultatValidation> {
        log.info("Running validation for dossier {}", dossier.reference)

        resultatRepository.deleteByDossierId(dossier.id!!)

        val results = mutableListOf<ResultatValidation>()
        val tol = BigDecimal(toleranceMontant)

        // R20 — Completude dossier: verification des pieces obligatoires
        run {
            val docTypes = dossier.documents.map { it.typeDocument }.toSet()
            val required = when (dossier.type) {
                DossierType.BC -> listOf(
                    TypeDocument.FACTURE to "Facture",
                    TypeDocument.BON_COMMANDE to "Bon de commande",
                    TypeDocument.CHECKLIST_AUTOCONTROLE to "Checklist autocontrole",
                    TypeDocument.TABLEAU_CONTROLE to "Tableau de controle",
                    TypeDocument.ORDRE_PAIEMENT to "Ordre de paiement"
                )
                DossierType.CONTRACTUEL -> listOf(
                    TypeDocument.FACTURE to "Facture",
                    TypeDocument.CONTRAT_AVENANT to "Contrat / Avenant",
                    TypeDocument.PV_RECEPTION to "PV de reception",
                    TypeDocument.CHECKLIST_AUTOCONTROLE to "Checklist autocontrole",
                    TypeDocument.ORDRE_PAIEMENT to "Ordre de paiement"
                )
            }
            val missing = required.filter { it.first !in docTypes }
            val present = required.size - missing.size
            results += ResultatValidation(
                dossier = dossier, regle = "R20",
                libelle = "Completude dossier (${present}/${required.size} pieces)",
                statut = when {
                    missing.isEmpty() -> StatutCheck.CONFORME
                    missing.size <= 2 -> StatutCheck.AVERTISSEMENT
                    else -> StatutCheck.NON_CONFORME
                },
                detail = if (missing.isEmpty()) "Toutes les pieces obligatoires sont presentes"
                    else "Manquant: ${missing.joinToString(", ") { it.second }}"
            )
        }

        val facture = dossier.factures.firstOrNull()
        val allFactures = dossier.factures
        val bc = dossier.bonCommande
        val op = dossier.ordrePaiement
        val contrat = dossier.contratAvenant
        val checklist = dossier.checklistAutocontrole
        val tableau = dossier.tableauControle
        val pv = dossier.pvReception
        val arf = dossier.attestationFiscale

        // R01 — Concordance montant TTC Facture ↔ BC
        if (dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontantWithFraction("R01", "Concordance montant TTC : Facture = BC",
                facture.montantTtc, bc.montantTtc, tol, dossier)
        }

        // R02 — Concordance montant HT Facture ↔ BC
        if (dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontantWithFraction("R02", "Concordance montant HT : Facture = BC",
                facture.montantHt, bc.montantHt, tol, dossier)
        }

        // R03 — Concordance TVA Facture ↔ BC
        if (dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontantWithFraction("R03", "Concordance TVA : Facture = BC",
                facture.montantTva, bc.montantTva, tol, dossier)
        }

        // R03b — Avertissement si taux TVA different (multi-taux possible)
        if (dossier.type == DossierType.BC && facture != null && bc != null) {
            val fTva = facture.tauxTva
            val bcTva = bc.tauxTva
            if (fTva != null && bcTva != null && fTva.compareTo(bcTva) != 0) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R03b",
                    libelle = "Taux TVA different entre Facture et BC (multi-taux possible)",
                    statut = StatutCheck.AVERTISSEMENT,
                    detail = "Facture: ${fTva}%, BC: ${bcTva}%",
                    valeurAttendue = bcTva.toPlainString(), valeurTrouvee = fTva.toPlainString()
                )
            }
        }

        // R04 — Montant OP = TTC facture (sans retenues)
        if (facture != null && op != null && op.retenues.isEmpty()) {
            results += checkMontant("R04", "Montant OP = TTC facture (sans retenues)",
                op.montantOperation, facture.montantTtc, tol, dossier)
        }

        // R05 — Montant OP = TTC - retenues
        if (facture != null && op != null && op.retenues.isNotEmpty()) {
            val totalRetenues = op.retenues.mapNotNull { it.montant }.fold(BigDecimal.ZERO) { acc, m -> acc.add(m) }
            val attendu = facture.montantTtc?.subtract(totalRetenues)
            results += checkMontant("R05", "Montant OP = TTC - retenues",
                op.montantOperation, attendu, tol, dossier)
        }

        // R06 — Verification arithmetique des retenues
        if (op != null) {
            for (ret in op.retenues) {
                if (ret.base != null && ret.taux != null && ret.montant != null) {
                    val calcule = ret.base!!.multiply(ret.taux).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
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
            val rawIces = listOfNotNull(facture.ice, arf?.ice)
            val normalizedIces = rawIces.mapNotNull { normalizeId(it) }.distinct()
            results += ResultatValidation(
                dossier = dossier, regle = "R09",
                libelle = "Coherence ICE fournisseur entre documents",
                statut = if (normalizedIces.size <= 1) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                detail = if (rawIces.size > 1) "ICE trouves: ${rawIces.joinToString(", ")}" else "ICE: ${rawIces.firstOrNull() ?: "non renseigne"}"
            )
        }

        // R10 — Coherence IF
        if (facture != null) {
            val rawIfs = listOfNotNull(facture.identifiantFiscal, arf?.identifiantFiscal)
            val normalizedIfs = rawIfs.mapNotNull { normalizeId(it) }.distinct()
            results += ResultatValidation(
                dossier = dossier, regle = "R10",
                libelle = "Coherence IF fournisseur entre documents",
                statut = if (normalizedIfs.size <= 1) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                detail = "IF trouves: ${rawIfs.joinToString(", ").ifEmpty { "non renseigne" }}"
            )
        }

        // R11 — Coherence RIB : collecte tous les RIBs de toutes les factures + OP
        if (allFactures.isNotEmpty() && op != null) {
            val allFactureRibs = allFactures.mapNotNull { f ->
                f.rib?.replace(WHITESPACE_RE, "")?.takeIf { it.isNotBlank() }
            }.distinct()
            // Also check donneesExtraites.ribs arrays for multi-RIB documents
            val allFactureRibsFromJson = allFactures.mapNotNull { f ->
                @Suppress("UNCHECKED_CAST")
                (f.document.donneesExtraites?.get("ribs") as? List<String>)
            }.flatten().map { it.replace(WHITESPACE_RE, "") }.filter { it.isNotBlank() }.distinct()
            val combinedFactureRibs = (allFactureRibs + allFactureRibsFromJson).distinct()

            val ribOp = op.rib?.replace(WHITESPACE_RE, "")
            val opRibs = listOfNotNull(ribOp) +
                ((@Suppress("UNCHECKED_CAST") (op.document.donneesExtraites?.get("ribs") as? List<String>))
                    ?.map { it.replace(WHITESPACE_RE, "") }?.filter { it.isNotBlank() } ?: emptyList())
            val allOpRibs = opRibs.distinct()

            val hasMatch = combinedFactureRibs.any { fr -> allOpRibs.any { or -> fr == or } }
            val docIds = allFactures.mapNotNull { it.document.id?.toString() } + listOfNotNull(op.document.id?.toString())

            results += ResultatValidation(
                dossier = dossier, regle = "R11",
                libelle = "Coherence RIB : Factures ↔ OP",
                statut = when {
                    combinedFactureRibs.isEmpty() || allOpRibs.isEmpty() -> StatutCheck.AVERTISSEMENT
                    hasMatch -> StatutCheck.CONFORME
                    else -> StatutCheck.NON_CONFORME
                },
                valeurAttendue = "Factures: ${combinedFactureRibs.joinToString(", ").ifBlank { "Aucun RIB" }}",
                valeurTrouvee = "OP: ${allOpRibs.joinToString(", ").ifBlank { "Aucun RIB" }}",
                detail = when {
                    combinedFactureRibs.size > 1 -> "${combinedFactureRibs.size} RIBs trouves dans les factures"
                    else -> null
                },
                documentIds = docIds.joinToString(",")
            )
        }

        // R14 — Coherence fournisseur entre documents
        run {
            val fournisseurs = (listOfNotNull(
                dossier.fournisseur,
                bc?.fournisseur,
                op?.beneficiaire,
                tableau?.fournisseur,
                checklist?.prestataire
            ) + allFactures.mapNotNull { it.fournisseur }).map { it.trim().lowercase() }.distinct()
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

        // R12 — Checklist complete : eclate en un resultat PAR point CK
        if (checklist != null) {
            val ckDocMapping = mapOf(
                1 to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE, TypeDocument.CONTRAT_AVENANT),
                2 to listOf(TypeDocument.FACTURE),
                3 to listOf(TypeDocument.FACTURE, TypeDocument.CONTRAT_AVENANT),
                4 to listOf(TypeDocument.CONTRAT_AVENANT),
                5 to listOf(TypeDocument.FACTURE, TypeDocument.CONTRAT_AVENANT),
                6 to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE, TypeDocument.PV_RECEPTION),
                7 to listOf(TypeDocument.FACTURE, TypeDocument.ATTESTATION_FISCALE),
                8 to listOf(TypeDocument.FACTURE, TypeDocument.CONTRAT_AVENANT, TypeDocument.ORDRE_PAIEMENT),
                9 to listOf(TypeDocument.FACTURE, TypeDocument.PV_RECEPTION, TypeDocument.BON_COMMANDE),
                10 to listOf(TypeDocument.PV_RECEPTION)
            )

            for (pt in checklist.points) {
                val num = pt.numero
                val regleCode = "R12.%02d".format(num)
                val sourceTypes = ckDocMapping[num] ?: emptyList()
                val sourceDocIds = dossier.documents
                    .filter { it.typeDocument in sourceTypes }
                    .mapNotNull { it.id?.toString() }
                val sourceLabels = sourceTypes.map { it.name }.joinToString(" + ")

                results += ResultatValidation(
                    dossier = dossier, regle = regleCode,
                    libelle = pt.description ?: "Point de controle $num",
                    statut = when (pt.estValide) {
                        true -> StatutCheck.CONFORME
                        false -> StatutCheck.NON_CONFORME
                        null -> StatutCheck.AVERTISSEMENT
                    },
                    detail = pt.observation,
                    source = "CHECKLIST",
                    valeurAttendue = sourceLabels.ifBlank { null },
                    documentIds = sourceDocIds.joinToString(",").ifBlank { null }
                )
            }

            // Also keep the aggregate R12 result
            val nonValides = checklist.points.filter { it.estValide == false }
            results += ResultatValidation(
                dossier = dossier, regle = "R12",
                libelle = "Checklist autocontrole complete",
                statut = when {
                    nonValides.isEmpty() && checklist.points.none { it.estValide == null } -> StatutCheck.CONFORME
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
            val valide = arf.dateEdition!!.plusMonths(6).isAfter(LocalDate.now())
            results += ResultatValidation(
                dossier = dossier, regle = "R18",
                libelle = "Validite attestation fiscale (6 mois)",
                statut = if (valide) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                detail = "Editee le ${arf.dateEdition}, valide jusqu'au ${arf.dateEdition!!.plusMonths(6)}"
            )
        }

        // R16 — Verification arithmetique HT + TVA = TTC
        if (facture != null && facture.montantHt != null && facture.montantTva != null && facture.montantTtc != null) {
            val calcTtc = facture.montantHt!!.add(facture.montantTva)
            results += checkMontant("R16", "Verification arithmetique : HT + TVA = TTC",
                facture.montantTtc, calcTtc, tol, dossier)
        }

        // R15 — Verification grille tarifaire x duree = HT facture (CONTRACTUEL)
        if (dossier.type == DossierType.CONTRACTUEL && contrat != null && facture != null) {
            val grilles = contrat.grillesTarifaires
            if (grilles.isNotEmpty()) {
                val months = computeMonths(pv?.periodeDebut, pv?.periodeFin, facture.periode)
                if (months != null && months > 0) {
                    val bdMonths = BigDecimal(months)
                    var expectedHt = BigDecimal.ZERO
                    for (g in grilles) {
                        val prix = g.prixUnitaireHt ?: continue
                        val multiplier = when (g.periodicite) {
                            Periodicite.MENSUEL -> bdMonths
                            Periodicite.TRIMESTRIEL -> bdMonths.divide(BigDecimal(3), 2, RoundingMode.HALF_UP)
                            Periodicite.ANNUEL -> bdMonths.divide(BigDecimal(12), 2, RoundingMode.HALF_UP)
                            Periodicite.JOURNALIER -> BigDecimal(months * 30)
                            null -> bdMonths
                        }
                        expectedHt = expectedHt.add(prix.multiply(multiplier))
                    }
                    results += checkMontant("R15",
                        "Grille tarifaire x ${months} mois = HT facture",
                        facture.montantHt, expectedHt, tol, dossier)
                } else {
                    results += ResultatValidation(
                        dossier = dossier, regle = "R15",
                        libelle = "Verification grille tarifaire x duree = HT facture",
                        statut = StatutCheck.AVERTISSEMENT,
                        detail = "Impossible de determiner la duree de la periode facturee"
                    )
                }
            }
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
        valeur1: BigDecimal?, valeur2: BigDecimal?,
        tolerance: BigDecimal, dossier: DossierPaiement
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

    private fun checkMontantWithFraction(
        regle: String, libelle: String,
        factureVal: BigDecimal?, bcVal: BigDecimal?,
        tolerance: BigDecimal, dossier: DossierPaiement
    ): ResultatValidation {
        val result = checkMontant(regle, libelle, factureVal, bcVal, tolerance, dossier)
        if (result.statut != StatutCheck.NON_CONFORME || factureVal == null || bcVal == null ||
            bcVal.signum() == 0 || factureVal >= bcVal) {
            return result
        }
        for (n in listOf(2, 3, 4, 6, 12)) {
            val expected = bcVal.divide(BigDecimal(n), 2, RoundingMode.HALF_UP)
            if (factureVal.subtract(expected).abs() <= tolerance) {
                return ResultatValidation(
                    dossier = dossier, regle = regle, libelle = libelle,
                    statut = StatutCheck.AVERTISSEMENT,
                    detail = "Facture = 1/${n} du BC (couverture partielle). Facture: ${factureVal.toPlainString()}, BC: ${bcVal.toPlainString()}",
                    valeurAttendue = bcVal.toPlainString(), valeurTrouvee = factureVal.toPlainString()
                )
            }
        }
        return result
    }

    private fun normalizeId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.replace(WHITESPACE_RE, "").trimStart('0').ifEmpty { "0" }
    }

    private fun computeMonths(debut: LocalDate?, fin: LocalDate?, periodeText: String?): Long? {
        if (debut != null && fin != null) {
            return ChronoUnit.MONTHS.between(debut, fin.plusDays(1)).coerceAtLeast(1)
        }
        if (periodeText != null) {
            val lower = periodeText.lowercase()
            if (lower.contains("t1") || lower.contains("t2") || lower.contains("t3") || lower.contains("t4")) return 3
            if (lower.contains("s1") || lower.contains("s2")) return 6
            val found = MONTH_NAMES.count { lower.contains(it) }
            if (found > 0) return found.toLong().coerceAtLeast(1)
        }
        return null
    }

    private fun matchReference(ref1: String?, ref2: String?): Boolean {
        if (ref1 == null || ref2 == null) return false
        val normalize = { s: String -> s.replace(REF_NORMALIZE_RE, "").trimStart('0').lowercase() }
        val n1 = normalize(ref1)
        val n2 = normalize(ref2)
        if (n1 == n2) return true
        val shorter = if (n1.length < n2.length) n1 else n2
        val longer = if (n1.length < n2.length) n2 else n1
        return shorter.length >= 4 && longer.contains(shorter)
    }
}
