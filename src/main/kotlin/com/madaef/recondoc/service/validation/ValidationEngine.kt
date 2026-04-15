package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.DossierRuleOverrideRepository
import com.madaef.recondoc.repository.dossier.ResultatValidationRepository
import com.madaef.recondoc.repository.dossier.RuleConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class ValidationEngine(
    private val resultatRepository: ResultatValidationRepository,
    private val ruleConfigRepo: RuleConfigRepository,
    private val overrideRepo: DossierRuleOverrideRepository,
    @Value("\${app.tolerance-montant:0.05}") private val toleranceMontant: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val WHITESPACE_RE = "[\\s\\-.]".toRegex()
        fun normalizeRib(rib: String?): String? = rib?.replace(WHITESPACE_RE, "")?.takeIf { it.isNotBlank() }
        private val REF_NORMALIZE_RE = "[\\s\\-_/.']+".toRegex()
        private val MONTH_NAMES = listOf(
            "janvier", "fevrier", "mars", "avril", "mai", "juin",
            "juillet", "aout", "septembre", "octobre", "novembre", "decembre"
        )

        val RULE_DEPENDENCIES: Map<String, Set<String>> = mapOf(
            "R01" to setOf("R02", "R03", "R03b"),
            "R02" to setOf("R01", "R03", "R03b"),
            "R03" to setOf("R01", "R02", "R03b"),
            "R03b" to setOf("R01", "R02", "R03"),
            "R04" to setOf("R05", "R16"),
            "R05" to setOf("R04", "R06", "R16"),
            "R06" to setOf("R05"),
            "R07" to setOf("R08"),
            "R08" to setOf("R07"),
            "R09" to setOf("R10"),
            "R10" to setOf("R09"),
            "R11" to setOf("R14"),
            "R14" to setOf("R11"),
            "R15" to setOf("R16", "R04"),
            "R16" to setOf("R04", "R05", "R15"),
            "R17a" to setOf("R17b"),
            "R17b" to setOf("R17a"),
            "R18" to emptySet(),
            "R20" to emptySet(),
            "R12" to emptySet(),
            "R13" to emptySet(),
        )
    }

    fun isRuleEnabled(dossierId: UUID, regle: String): Boolean {
        val override = overrideRepo.findByDossierIdAndRegle(dossierId, regle)
        if (override != null) return override.enabled
        val global = ruleConfigRepo.findByRegle(regle)
        return global?.enabled ?: true
    }

    @Transactional
    fun validate(dossier: DossierPaiement): List<ResultatValidation> {
        log.info("Running validation for dossier {}", dossier.reference)
        dossier.resultatsValidation.clear()
        resultatRepository.deleteByDossierId(dossier.id!!)
        resultatRepository.flush()

        val results = runAllRules(dossier)
        results.forEach { it.dateExecution = LocalDateTime.now() }
        resultatRepository.saveAll(results)

        val conformes = results.count { it.statut == StatutCheck.CONFORME }
        val nonConformes = results.count { it.statut == StatutCheck.NON_CONFORME }
        log.info("Validation complete for {}: {}/{} conforme, {} non-conforme",
            dossier.reference, conformes, results.size, nonConformes)

        return results
    }

    @Transactional
    fun rerunRule(dossier: DossierPaiement, regle: String): List<ResultatValidation> {
        val rulesToRun = mutableSetOf(regle)
        RULE_DEPENDENCIES[regle]?.let { rulesToRun.addAll(it) }
        if (regle == "R12" || regle.startsWith("R12.")) {
            rulesToRun.add("R12")
            for (i in 1..10) rulesToRun.add("R12.%02d".format(i))
        }

        val existingResults = resultatRepository.findByDossierId(dossier.id!!)
        val toDelete = existingResults.filter { it.regle in rulesToRun }
        resultatRepository.deleteAll(toDelete)
        resultatRepository.flush()

        val allResults = runAllRules(dossier)
        val filtered = allResults.filter { it.regle in rulesToRun }

        filtered.forEach { it.dateExecution = LocalDateTime.now() }
        resultatRepository.saveAll(filtered)

        return filtered
    }

    private fun runAllRules(dossier: DossierPaiement): List<ResultatValidation> {
        val results = mutableListOf<ResultatValidation>()
        val tol = BigDecimal(toleranceMontant)
        val dossierId = dossier.id!!

        if (isRuleEnabled(dossierId, "R20")) {
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

        if (isRuleEnabled(dossierId, "R01") && dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontantWithFraction("R01", "Concordance montant TTC : Facture = BC",
                facture.montantTtc, bc.montantTtc, tol, dossier)
        }

        if (isRuleEnabled(dossierId, "R02") && dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontantWithFraction("R02", "Concordance montant HT : Facture = BC",
                facture.montantHt, bc.montantHt, tol, dossier)
        }

        if (isRuleEnabled(dossierId, "R03") && dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontantWithFraction("R03", "Concordance TVA : Facture = BC",
                facture.montantTva, bc.montantTva, tol, dossier)
        }

        if (isRuleEnabled(dossierId, "R03b") && dossier.type == DossierType.BC && facture != null && bc != null) {
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

        if (isRuleEnabled(dossierId, "R04") && facture != null && op != null && op.retenues.isEmpty()) {
            results += checkMontant("R04", "Montant OP = TTC facture (sans retenues)",
                op.montantOperation, facture.montantTtc, tol, dossier)
        }

        if (isRuleEnabled(dossierId, "R05") && facture != null && op != null && op.retenues.isNotEmpty()) {
            val totalRetenues = op.retenues.mapNotNull { it.montant }.fold(BigDecimal.ZERO) { acc, m -> acc.add(m) }
            val attendu = facture.montantTtc?.subtract(totalRetenues)
            results += checkMontant("R05", "Montant OP = TTC - retenues",
                op.montantOperation, attendu, tol, dossier)
        }

        if (isRuleEnabled(dossierId, "R06") && op != null) {
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

        if (isRuleEnabled(dossierId, "R07") && facture != null && op != null) {
            val ok = matchReference(op.referenceFacture, facture.numeroFacture)
            results += ResultatValidation(
                dossier = dossier, regle = "R07",
                libelle = "Reference facture citee dans l'OP",
                statut = if (ok) StatutCheck.CONFORME else if (op.referenceFacture == null) StatutCheck.AVERTISSEMENT else StatutCheck.NON_CONFORME,
                valeurAttendue = facture.numeroFacture, valeurTrouvee = op.referenceFacture
            )
        }

        if (isRuleEnabled(dossierId, "R08") && op != null) {
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

        if (isRuleEnabled(dossierId, "R09")) {
            val iceFacture = facture?.ice?.trim()?.takeIf { it.isNotBlank() }
            val iceArf = arf?.ice?.trim()?.takeIf { it.isNotBlank() }
            val normalizedIces = listOfNotNull(iceFacture, iceArf).mapNotNull { normalizeId(it) }.distinct()
            val statut = when {
                iceFacture == null && iceArf == null -> StatutCheck.AVERTISSEMENT
                iceFacture == null || iceArf == null -> StatutCheck.AVERTISSEMENT
                normalizedIces.size == 1 -> StatutCheck.CONFORME
                else -> StatutCheck.NON_CONFORME
            }
            results += ResultatValidation(
                dossier = dossier, regle = "R09",
                libelle = "Coherence ICE fournisseur entre documents",
                statut = statut,
                valeurAttendue = iceFacture ?: "Absent de la facture",
                valeurTrouvee = iceArf ?: "Absent de l'attestation fiscale",
                detail = when (statut) {
                    StatutCheck.AVERTISSEMENT -> "ICE absent dans ${if (iceFacture == null) "la facture" else "l'attestation fiscale"}"
                    StatutCheck.NON_CONFORME -> "ICE differents: facture=$iceFacture, attestation=$iceArf"
                    else -> "ICE identiques"
                }
            )
        }

        if (isRuleEnabled(dossierId, "R10")) {
            val ifFacture = facture?.identifiantFiscal?.trim()?.takeIf { it.isNotBlank() }
            val ifArf = arf?.identifiantFiscal?.trim()?.takeIf { it.isNotBlank() }
            val normalizedIfs = listOfNotNull(ifFacture, ifArf).mapNotNull { normalizeId(it) }.distinct()
            val statut = when {
                ifFacture == null && ifArf == null -> StatutCheck.AVERTISSEMENT
                ifFacture == null || ifArf == null -> StatutCheck.AVERTISSEMENT
                normalizedIfs.size == 1 -> StatutCheck.CONFORME
                else -> StatutCheck.NON_CONFORME
            }
            results += ResultatValidation(
                dossier = dossier, regle = "R10",
                libelle = "Coherence IF fournisseur entre documents",
                statut = statut,
                valeurAttendue = ifFacture ?: "Absent de la facture",
                valeurTrouvee = ifArf ?: "Absent de l'attestation fiscale",
                detail = when (statut) {
                    StatutCheck.AVERTISSEMENT -> "IF absent dans ${if (ifFacture == null) "la facture" else "l'attestation fiscale"}"
                    StatutCheck.NON_CONFORME -> "IF differents: facture=$ifFacture, attestation=$ifArf"
                    else -> "IF identiques"
                }
            )
        }

        if (isRuleEnabled(dossierId, "R11") && allFactures.isNotEmpty() && op != null) {
            val allFactureRibs = allFactures.mapNotNull { f -> normalizeRib(f.rib) }.distinct()
            val allFactureRibsFromJson = allFactures.mapNotNull { f ->
                @Suppress("UNCHECKED_CAST")
                (f.document.donneesExtraites?.get("ribs") as? List<String>)
            }.flatten().mapNotNull { normalizeRib(it) }.distinct()
            val combinedFactureRibs = (allFactureRibs + allFactureRibsFromJson).distinct()

            val opRibs = listOfNotNull(normalizeRib(op.rib)) +
                ((@Suppress("UNCHECKED_CAST") (op.document.donneesExtraites?.get("ribs") as? List<String>))
                    ?.mapNotNull { normalizeRib(it) } ?: emptyList())
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

        if (isRuleEnabled(dossierId, "R14")) {
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

        if (isRuleEnabled(dossierId, "R12")) {
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

            val ckResults = executeChecklistPoints(dossier, facture, allFactures, bc, op, contrat, pv, arf, checklist, tol, ckDocMapping)
            results += ckResults

            val nbOk = ckResults.count { it.statut == StatutCheck.CONFORME }
            val nbKo = ckResults.count { it.statut == StatutCheck.NON_CONFORME }
            results += ResultatValidation(
                dossier = dossier, regle = "R12",
                libelle = "Checklist autocontrole complete",
                statut = when {
                    nbKo == 0 && ckResults.none { it.statut == StatutCheck.AVERTISSEMENT } -> StatutCheck.CONFORME
                    nbKo > 0 -> StatutCheck.NON_CONFORME
                    else -> StatutCheck.AVERTISSEMENT
                },
                detail = "${nbOk}/${ckResults.size} points conformes" +
                    if (nbKo > 0) ", ${nbKo} non conforme(s)" else ""
            )
        }

        if (isRuleEnabled(dossierId, "R13") && tableau != null) {
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

        run {
            val dateBcContrat = bc?.dateBc ?: contrat?.dateSignature
            val dateFacture = facture?.dateFacture
            val dateOp = op?.dateEmission
            if (isRuleEnabled(dossierId, "R17a") && dateBcContrat != null && dateFacture != null) {
                val ok = !dateFacture.isBefore(dateBcContrat)
                results += ResultatValidation(
                    dossier = dossier, regle = "R17a",
                    libelle = "Chronologie : date BC/Contrat <= date Facture",
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.AVERTISSEMENT,
                    valeurAttendue = dateBcContrat.toString(), valeurTrouvee = dateFacture.toString()
                )
            }
            if (isRuleEnabled(dossierId, "R17b") && dateFacture != null && dateOp != null) {
                val ok = !dateOp.isBefore(dateFacture)
                results += ResultatValidation(
                    dossier = dossier, regle = "R17b",
                    libelle = "Chronologie : date Facture <= date OP",
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.AVERTISSEMENT,
                    valeurAttendue = dateFacture.toString(), valeurTrouvee = dateOp.toString()
                )
            }
        }

        if (isRuleEnabled(dossierId, "R18") && arf != null && arf.dateEdition != null) {
            val valide = arf.dateEdition!!.plusMonths(6).isAfter(LocalDate.now())
            results += ResultatValidation(
                dossier = dossier, regle = "R18",
                libelle = "Validite attestation fiscale (6 mois)",
                statut = if (valide) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                detail = "Editee le ${arf.dateEdition}, valide jusqu'au ${arf.dateEdition!!.plusMonths(6)}"
            )
        }

        if (isRuleEnabled(dossierId, "R16") && facture != null && facture.montantHt != null && facture.montantTva != null && facture.montantTtc != null) {
            val calcTtc = facture.montantHt!!.add(facture.montantTva)
            results += checkMontant("R16", "Verification arithmetique : HT + TVA = TTC",
                facture.montantTtc, calcTtc, tol, dossier)
        }

        if (isRuleEnabled(dossierId, "R15") && dossier.type == DossierType.CONTRACTUEL && contrat != null && facture != null) {
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

        val ruleDocTypes = mapOf(
            "R01" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE),
            "R02" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE),
            "R03" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE),
            "R03b" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE),
            "R04" to listOf(TypeDocument.FACTURE, TypeDocument.ORDRE_PAIEMENT),
            "R05" to listOf(TypeDocument.FACTURE, TypeDocument.ORDRE_PAIEMENT),
            "R07" to listOf(TypeDocument.FACTURE, TypeDocument.ORDRE_PAIEMENT),
            "R08" to listOf(TypeDocument.BON_COMMANDE, TypeDocument.CONTRAT_AVENANT, TypeDocument.ORDRE_PAIEMENT),
            "R09" to listOf(TypeDocument.FACTURE, TypeDocument.ATTESTATION_FISCALE),
            "R10" to listOf(TypeDocument.FACTURE, TypeDocument.ATTESTATION_FISCALE),
            "R14" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE, TypeDocument.ORDRE_PAIEMENT),
            "R15" to listOf(TypeDocument.FACTURE, TypeDocument.CONTRAT_AVENANT, TypeDocument.PV_RECEPTION),
            "R16" to listOf(TypeDocument.FACTURE),
            "R17" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE, TypeDocument.ORDRE_PAIEMENT),
            "R18" to listOf(TypeDocument.ATTESTATION_FISCALE),
            "R20" to emptyList(),
        )
        for (r in results) {
            if (r.documentIds == null) {
                val types = ruleDocTypes[r.regle]
                if (types != null && types.isNotEmpty()) {
                    r.documentIds = dossier.documents
                        .filter { it.typeDocument in types }
                        .mapNotNull { it.id?.toString() }
                        .joinToString(",")
                        .ifBlank { null }
                }
            }
        }

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

    private fun executeChecklistPoints(
        dossier: DossierPaiement,
        facture: Facture?, allFactures: List<Facture>,
        bc: BonCommande?, op: OrdrePaiement?, contrat: ContratAvenant?,
        pv: PvReception?, arf: AttestationFiscale?,
        checklist: ChecklistAutocontrole?,
        tol: BigDecimal,
        ckDocMapping: Map<Int, List<TypeDocument>>
    ): List<ResultatValidation> {
        val ckResults = mutableListOf<ResultatValidation>()

        fun docIds(num: Int): String? {
            val types = ckDocMapping[num] ?: return null
            return dossier.documents.filter { it.typeDocument in types }
                .mapNotNull { it.id?.toString() }.joinToString(",").ifBlank { null }
        }

        fun ckPoint(pt: PointControle?): Pair<Boolean?, String?> =
            if (pt != null) (pt.estValide to pt.observation) else (null to null)

        val points = checklist?.points?.sortedBy { it.numero } ?: emptyList()

        // CK01: Concordance facture / modalites contractuelles / livrables
        run {
            val pt = points.find { it.numero == 1 }
            val (ckValide, obs) = ckPoint(pt)
            val hasBcOrContrat = bc != null || contrat != null
            val hasFacture = facture != null
            val montantMatch = when {
                facture == null -> false
                bc != null -> facture.montantTtc != null && bc.montantTtc != null &&
                    facture.montantTtc!!.subtract(bc.montantTtc).abs() <= tol
                contrat != null && contrat.grillesTarifaires.isNotEmpty() -> true
                else -> false
            }
            val sysStatut = when {
                !hasFacture || !hasBcOrContrat -> StatutCheck.AVERTISSEMENT
                montantMatch -> StatutCheck.CONFORME
                else -> StatutCheck.NON_CONFORME
            }
            val detail = buildString {
                if (!hasFacture) append("Facture manquante. ")
                if (!hasBcOrContrat) append("BC/Contrat manquant. ")
                if (hasFacture && hasBcOrContrat) {
                    if (montantMatch) append("Montants concordants. ")
                    else append("Montants discordants (facture vs BC/contrat). ")
                }
                if (obs != null) append("Autocontrole: $obs")
            }
            val finalStatut = mergeStatut(sysStatut, ckValide)
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.01",
                libelle = pt?.description ?: "Concordance facture / modalites contractuelles / livrables",
                statut = finalStatut, detail = detail.trim(),
                source = "CHECKLIST",
                valeurAttendue = if (bc != null) "BC: ${bc.montantTtc?.toPlainString()}" else contrat?.referenceContrat,
                valeurTrouvee = facture?.montantTtc?.toPlainString(),
                documentIds = docIds(1)
            )
        }

        // CK02: Verification arithmetique des montants
        run {
            val pt = points.find { it.numero == 2 }
            val (ckValide, obs) = ckPoint(pt)
            val htOk = facture != null && facture.montantHt != null && facture.montantTva != null && facture.montantTtc != null &&
                facture.montantHt!!.add(facture.montantTva).subtract(facture.montantTtc).abs() <= tol
            val sysStatut = when {
                facture == null -> StatutCheck.AVERTISSEMENT
                htOk -> StatutCheck.CONFORME
                else -> StatutCheck.NON_CONFORME
            }
            val detail = if (htOk) "HT + TVA = TTC verifie" else if (facture == null) "Facture manquante" else
                "HT(${facture.montantHt}) + TVA(${facture.montantTva}) != TTC(${facture.montantTtc})"
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.02",
                libelle = pt?.description ?: "Verification arithmetique des montants",
                statut = mergeStatut(sysStatut, ckValide),
                detail = listOfNotNull(detail, obs?.let { "Autocontrole: $it" }).joinToString(". "),
                source = "CHECKLIST",
                valeurAttendue = facture?.let { "${it.montantHt} + ${it.montantTva}" },
                valeurTrouvee = facture?.montantTtc?.toPlainString(),
                documentIds = docIds(2)
            )
        }

        // CK03: Respect du delai d'execution
        run {
            val pt = points.find { it.numero == 3 }
            val (ckValide, obs) = ckPoint(pt)
            val dateBcContrat = bc?.dateBc ?: contrat?.dateSignature
            val dateFactureVal = facture?.dateFacture
            val sysStatut = when {
                dateBcContrat == null || dateFactureVal == null -> StatutCheck.AVERTISSEMENT
                !dateFactureVal.isBefore(dateBcContrat) -> StatutCheck.CONFORME
                else -> StatutCheck.NON_CONFORME
            }
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.03",
                libelle = pt?.description ?: "Respect du delai d'execution",
                statut = mergeStatut(sysStatut, ckValide),
                detail = listOfNotNull(
                    if (dateBcContrat != null && dateFactureVal != null) "BC/Contrat: $dateBcContrat, Facture: $dateFactureVal" else "Dates manquantes",
                    obs?.let { "Autocontrole: $it" }
                ).joinToString(". "),
                source = "CHECKLIST",
                valeurAttendue = dateBcContrat?.toString(),
                valeurTrouvee = dateFactureVal?.toString(),
                documentIds = docIds(3)
            )
        }

        // CK04: Modifications / avenants
        run {
            val pt = points.find { it.numero == 4 }
            val (ckValide, obs) = ckPoint(pt)
            val hasAvenant = contrat?.numeroAvenant != null
            val sysStatut = if (hasAvenant) StatutCheck.AVERTISSEMENT else StatutCheck.CONFORME
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.04",
                libelle = pt?.description ?: "Modifications / avenants (plafonds et variations)",
                statut = mergeStatut(sysStatut, ckValide),
                detail = listOfNotNull(
                    if (hasAvenant) "Avenant detecte: ${contrat?.numeroAvenant}" else "Aucun avenant detecte",
                    obs?.let { "Autocontrole: $it" }
                ).joinToString(". "),
                source = "CHECKLIST",
                documentIds = docIds(4)
            )
        }

        // CK05: Retenues de garantie et penalites
        run {
            val pt = points.find { it.numero == 5 }
            val (ckValide, obs) = ckPoint(pt)
            val hasRetenues = op != null && op.retenues.isNotEmpty()
            val retenuesOk = if (hasRetenues) {
                op!!.retenues.all { it.base != null && it.taux != null && it.montant != null &&
                    it.base!!.multiply(it.taux).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
                        .subtract(it.montant).abs() <= tol }
            } else true
            val sysStatut = when {
                op == null -> StatutCheck.AVERTISSEMENT
                !hasRetenues -> StatutCheck.CONFORME
                retenuesOk -> StatutCheck.CONFORME
                else -> StatutCheck.NON_CONFORME
            }
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.05",
                libelle = pt?.description ?: "Retenues de garantie et penalites de retard",
                statut = mergeStatut(sysStatut, ckValide),
                detail = listOfNotNull(
                    if (hasRetenues) "${op!!.retenues.size} retenue(s) detectee(s), calcul ${if (retenuesOk) "correct" else "incorrect"}"
                    else "Aucune retenue",
                    obs?.let { "Autocontrole: $it" }
                ).joinToString(". "),
                source = "CHECKLIST",
                documentIds = docIds(5)
            )
        }

        // CK06: Signatures et visas
        run {
            val pt = points.find { it.numero == 6 }
            val (ckValide, obs) = ckPoint(pt)
            val pvSigne = pv != null && (pv.signataireMadaef != null || pv.signataireFournisseur != null)
            val bcSigne = bc?.signataire != null
            val sysStatut = when {
                ckValide == true -> StatutCheck.CONFORME
                ckValide == false -> StatutCheck.NON_CONFORME
                pvSigne || bcSigne -> StatutCheck.AVERTISSEMENT
                else -> StatutCheck.AVERTISSEMENT
            }
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.06",
                libelle = pt?.description ?: "Signatures et visas des personnes habilitees",
                statut = sysStatut,
                detail = listOfNotNull(
                    if (pvSigne) "PV signe" else null,
                    if (bcSigne) "BC signe par ${bc?.signataire}" else null,
                    if (!pvSigne && !bcSigne) "Aucune signature detectee" else null,
                    obs?.let { "Autocontrole: $it" }
                ).joinToString(". "),
                source = "CHECKLIST",
                documentIds = docIds(6)
            )
        }

        // CK07: Conformite reglementaire (ICE, IF, RC, CNSS)
        run {
            val pt = points.find { it.numero == 7 }
            val (ckValide, obs) = ckPoint(pt)
            val iceOk = facture?.ice?.isNotBlank() == true
            val ifOk = facture?.identifiantFiscal?.isNotBlank() == true
            val rcOk = facture?.rc?.isNotBlank() == true
            val arfOk = arf?.estEnRegle == true
            val nbPresents = listOf(iceOk, ifOk, rcOk).count { it }
            val sysStatut = when {
                facture == null -> StatutCheck.AVERTISSEMENT
                nbPresents == 3 && arfOk -> StatutCheck.CONFORME
                nbPresents >= 2 -> StatutCheck.AVERTISSEMENT
                else -> StatutCheck.NON_CONFORME
            }
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.07",
                libelle = pt?.description ?: "Conformite reglementaire de la facture",
                statut = mergeStatut(sysStatut, ckValide),
                detail = listOfNotNull(
                    "ICE: ${if (iceOk) facture?.ice else "absent"}",
                    "IF: ${if (ifOk) facture?.identifiantFiscal else "absent"}",
                    "RC: ${if (rcOk) facture?.rc else "absent"}",
                    if (arf != null) "Attestation: ${if (arfOk) "en regle" else "non conforme"}" else null,
                    obs?.let { "Autocontrole: $it" }
                ).joinToString(". "),
                source = "CHECKLIST",
                valeurAttendue = "ICE + IF + RC presents",
                valeurTrouvee = "$nbPresents/3 identifiants",
                documentIds = docIds(7)
            )
        }

        // CK08: Conformite RIB contractuel vs facture
        run {
            val pt = points.find { it.numero == 8 }
            val (ckValide, obs) = ckPoint(pt)
            val factureRib = normalizeRib(facture?.rib)
            val opRib = normalizeRib(op?.rib)
            val sysStatut = when {
                factureRib == null || opRib == null -> StatutCheck.AVERTISSEMENT
                factureRib == opRib -> StatutCheck.CONFORME
                else -> StatutCheck.NON_CONFORME
            }
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.08",
                libelle = pt?.description ?: "Conformite du RIB contractuel vs facture",
                statut = mergeStatut(sysStatut, ckValide),
                detail = listOfNotNull(
                    "RIB Facture: ${factureRib ?: "absent"}, RIB OP: ${opRib ?: "absent"}",
                    obs?.let { "Autocontrole: $it" }
                ).joinToString(". "),
                source = "CHECKLIST",
                valeurAttendue = opRib,
                valeurTrouvee = factureRib,
                documentIds = docIds(8)
            )
        }

        // CK09: Conformite BL / PV de reception
        run {
            val pt = points.find { it.numero == 9 }
            val (ckValide, obs) = ckPoint(pt)
            val hasPv = pv != null
            val pvRefMatch = if (pv?.referenceContrat != null && contrat?.referenceContrat != null)
                matchReference(pv.referenceContrat, contrat.referenceContrat) else null
            val sysStatut = when {
                !hasPv -> StatutCheck.AVERTISSEMENT
                pvRefMatch == true -> StatutCheck.CONFORME
                pvRefMatch == false -> StatutCheck.NON_CONFORME
                else -> StatutCheck.AVERTISSEMENT
            }
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.09",
                libelle = pt?.description ?: "Conformite BL / PV de reception",
                statut = mergeStatut(sysStatut, ckValide),
                detail = listOfNotNull(
                    if (hasPv) "PV present" else "PV manquant",
                    if (pvRefMatch != null) "Reference ${if (pvRefMatch) "coherente" else "incoherente"}" else null,
                    obs?.let { "Autocontrole: $it" }
                ).joinToString(". "),
                source = "CHECKLIST",
                documentIds = docIds(9)
            )
        }

        // CK10: Habilitations des signataires des receptions
        run {
            val pt = points.find { it.numero == 10 }
            val (ckValide, obs) = ckPoint(pt)
            val hasPvSignataires = pv != null && (pv.signataireMadaef != null || pv.signataireFournisseur != null)
            val sysStatut = when {
                ckValide == true -> StatutCheck.CONFORME
                ckValide == false -> StatutCheck.NON_CONFORME
                hasPvSignataires -> StatutCheck.AVERTISSEMENT
                else -> StatutCheck.AVERTISSEMENT
            }
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.10",
                libelle = pt?.description ?: "Habilitations des signataires des receptions",
                statut = sysStatut,
                detail = listOfNotNull(
                    if (hasPvSignataires) "Signataires: ${listOfNotNull(pv?.signataireMadaef, pv?.signataireFournisseur).joinToString(", ")}"
                    else "Aucun signataire detecte dans le PV",
                    obs?.let { "Autocontrole: $it" }
                ).joinToString(". "),
                source = "CHECKLIST",
                documentIds = docIds(10)
            )
        }

        return ckResults
    }

    private fun mergeStatut(systemStatut: StatutCheck, checklistValide: Boolean?): StatutCheck {
        if (checklistValide == null) return systemStatut
        val ckStatut = if (checklistValide) StatutCheck.CONFORME else StatutCheck.NON_CONFORME
        return if (systemStatut == StatutCheck.NON_CONFORME || ckStatut == StatutCheck.NON_CONFORME) StatutCheck.NON_CONFORME
        else if (systemStatut == StatutCheck.AVERTISSEMENT || ckStatut == StatutCheck.AVERTISSEMENT) StatutCheck.AVERTISSEMENT
        else StatutCheck.CONFORME
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
