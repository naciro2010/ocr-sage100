package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.DossierRuleOverrideRepository
import com.madaef.recondoc.repository.dossier.ResultatValidationRepository
import com.madaef.recondoc.repository.dossier.RuleConfigRepository
import com.madaef.recondoc.service.QrCodeService
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
    private val ruleConfigCache: RuleConfigCache,
    private val customRuleService: CustomRuleService,
    private val factureRepository: com.madaef.recondoc.repository.dossier.FactureRepository,
    private val fournisseurMatchingService: com.madaef.recondoc.service.fournisseur.FournisseurMatchingService,
    private val engagementDispatcher: com.madaef.recondoc.service.validation.engagement.EngagementValidationDispatcher,
    @Value("\${app.tolerance-montant:0.05}") private val toleranceMontant: String,
    @Value("\${app.tolerance-montant-pct:0.005}") private val toleranceMontantPct: String,
    @Value("\${app.anti-doublon.lookback-months:12}") private val antiDoublonLookbackMonths: Long,
    @Value("\${app.anti-doublon.date-tolerance-days:3}") private val antiDoublonDateToleranceDays: Long,
    @Value("\${app.anti-doublon.montant-tolerance-pct:0.01}") private val antiDoublonMontantTolerancePct: String,
    @Value("\${app.completude-lignes.seuil-ttc:50000}") private val completudeLignesSeuilTtc: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // Les constantes metier (TYPE_LABELS, DEFAULT_REQUIRED_BY_TYPE, RULE_DEPENDENCIES)
        // vivent dans [RuleConstants]. Les helpers deterministes (checkMontant,
        // matchReference, normalizeRib, parseLocalDate, docAmount, docStr,
        // parseBooleanish, etc.) sont des fonctions top-level du package
        // `com.madaef.recondoc.service.validation` (voir ValidationHelpers.kt).
        //
        // L'alias RULE_DEPENDENCIES reste expose parce que les tests et le
        // catalogue (RuleCatalog) y referencent directement via l'ancienne
        // constante. Le jour ou tous les appelants migrent vers RuleConstants,
        // cet alias peut disparaitre.
        val RULE_DEPENDENCIES: Map<String, Set<String>> get() = RuleConstants.RULE_DEPENDENCIES
    }

    private data class CorrectionSnapshot(
        val statut: StatutCheck, val statutOriginal: String?,
        val commentaire: String?, val corrigePar: String?,
        val dateCorrection: LocalDateTime?,
        val valeurTrouvee: String?, val valeurAttendue: String?,
        val documentIds: String?
    )

    private fun loadEnabledRules(dossierId: UUID): (String) -> Boolean {
        val overrides = ruleConfigCache.listOverrides(dossierId).associate { it.regle to it.enabled }
        val globals = ruleConfigCache.listGlobal().associate { it.regle to it.enabled }
        return { regle -> overrides[regle] ?: globals[regle] ?: true }
    }

    fun isRuleEnabled(dossierId: UUID, regle: String): Boolean {
        val override = ruleConfigCache.listOverrides(dossierId).firstOrNull { it.regle == regle }
        if (override != null) return override.enabled
        val global = ruleConfigCache.findGlobal(regle)
        return global?.enabled ?: true
    }

    /**
     * Returns the set of rules that will be re-executed if [regle] is relaunched.
     * Includes the rule itself plus its declared dependencies (R12 expands to R12.01-R12.10).
     * Used by the UI to preview the cascade scope before running.
     */
    fun getCascadeScope(regle: String): Set<String> {
        val scope = mutableSetOf(regle)
        RULE_DEPENDENCIES[regle]?.let { scope.addAll(it) }
        if (regle == "R12" || regle.startsWith("R12.")) {
            scope.add("R12")
            for (i in 1..10) scope.add("R12.%02d".format(i))
        }
        return scope
    }

    // evidence(...) est maintenant top-level dans ValidationHelpers.kt (meme package).

    @Transactional
    fun validate(dossier: DossierPaiement): List<ResultatValidation> {
        log.info("Running validation for dossier {}", dossier.reference)
        dossier.resultatsValidation.clear()
        resultatRepository.deleteByDossierId(dossier.id!!)
        resultatRepository.flush()

        val isEnabled = loadEnabledRules(dossier.id!!)
        val t0 = System.nanoTime()
        val results = runAllRules(dossier, isEnabled).toMutableList()

        // Couche engagement (R-E/R-M/R-B/R-C) : no-op si dossier non rattache
        results += engagementDispatcher.validate(dossier)

        val totalMs = (System.nanoTime() - t0) / 1_000_000
        results.forEach { it.dateExecution = LocalDateTime.now() }
        resultatRepository.saveAll(results)

        val conformes = results.count { it.statut == StatutCheck.CONFORME }
        val nonConformes = results.count { it.statut == StatutCheck.NON_CONFORME }
        log.info("Validation complete for {}: {}/{} conforme, {} non-conforme (total {}ms)",
            dossier.reference, conformes, results.size, nonConformes, totalMs)

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
        val corrected = existingResults
            .filter { it.regle in rulesToRun && it.statutOriginal != null }
            .associate {
                it.regle to CorrectionSnapshot(
                    statut = it.statut,
                    statutOriginal = it.statutOriginal,
                    commentaire = it.commentaire,
                    corrigePar = it.corrigePar,
                    dateCorrection = it.dateCorrection,
                    valeurTrouvee = it.valeurTrouvee,
                    valeurAttendue = it.valeurAttendue,
                    documentIds = it.documentIds,
                )
            }

        val toDelete = existingResults.filter { it.regle in rulesToRun }
        resultatRepository.deleteAll(toDelete)
        resultatRepository.flush()

        val isEnabled: (String) -> Boolean = { it in rulesToRun }
        val t0 = System.nanoTime()
        val allResults = runAllRules(dossier, isEnabled)
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        allResults.forEach { r ->
            r.dateExecution = LocalDateTime.now()
            val prev = corrected[r.regle] ?: return@forEach
            r.statutOriginal = r.statut.name
            r.statut = prev.statut
            r.commentaire = prev.commentaire
            r.corrigePar = prev.corrigePar
            r.dateCorrection = prev.dateCorrection
            r.valeurTrouvee = prev.valeurTrouvee
            r.valeurAttendue = prev.valeurAttendue
            r.documentIds = prev.documentIds
        }
        resultatRepository.saveAll(allResults)
        log.info("Rerun rule {} on dossier {}: {} results ({}ms)", regle, dossier.reference, allResults.size, totalMs)

        return allResults
    }

    private inline fun <T> measureRule(code: String, results: MutableList<ResultatValidation>, block: () -> T): T {
        val startIdx = results.size
        val t0 = System.nanoTime()
        val out = block()
        val dur = (System.nanoTime() - t0) / 1_000_000
        for (i in startIdx until results.size) {
            val r = results[i]
            if (r.durationMs == null && (r.regle == code || r.regle.startsWith("$code."))) {
                r.durationMs = dur
            }
        }
        return out
    }

    private fun runAllRules(dossier: DossierPaiement, isEnabled: (String) -> Boolean): List<ResultatValidation> {
        val results = mutableListOf<ResultatValidation>()
        // tol  = tolerance absolue en MAD (5 centimes par defaut) pour comparer
        //        deux totaux issus de documents differents (ecart d'arrondi).
        // tolPct = tolerance relative (0.5% par defaut) pour les controles
        //        proportionnels lignes/cumuls. Sans cette distinction, les
        //        regles R16b et R01g reutilisaient `tol` comme pourcentage,
        //        ce qui revenait a accepter 5% d'ecart sur une ligne — trop laxiste.
        val tol = BigDecimal(toleranceMontant)
        val tolPct = BigDecimal(toleranceMontantPct)
        // Dedupe defensif : l'EntityGraph qui charge documents + factures + resultatsValidation
        // peut produire un produit cartesien sur les bags (List<Facture>), ce qui multiplie
        // chaque facture et fait exploser la liste documentIds (UI R11 = dizaines de chips).
        val factures = dossier.factures.distinctBy { it.id ?: it }
        val ctx = ValidationContext(
            dossier = dossier,
            facture = factures.firstOrNull(),
            allFactures = factures,
            bc = dossier.bonCommande,
            op = dossier.ordrePaiement,
            contrat = dossier.contratAvenant,
            pv = dossier.pvReception,
            arf = dossier.attestationFiscale,
            checklist = dossier.checklistAutocontrole,
            tableau = dossier.tableauControle,
            tol = tol
        )

        if (isEnabled("R20")) {
            val docTypes = dossier.documents.map { it.typeDocument }.toSet()
            val required = resolveRequiredDocuments(dossier.type, dossier.requiredDocuments)
            val missing = required.filter { it.first !in docTypes }
            val present = required.size - missing.size
            val source = if (dossier.requiredDocuments.isNullOrBlank()) "defaut" else "personnalisee"
            results += ResultatValidation(
                dossier = dossier, regle = "R20",
                libelle = "Completude dossier (${present}/${required.size} pieces, liste $source)",
                statut = when {
                    required.isEmpty() -> StatutCheck.CONFORME
                    missing.isEmpty() -> StatutCheck.CONFORME
                    missing.size <= 2 -> StatutCheck.AVERTISSEMENT
                    else -> StatutCheck.NON_CONFORME
                },
                detail = when {
                    required.isEmpty() -> "Aucune piece obligatoire configuree — completude non evaluee"
                    missing.isEmpty() -> "Toutes les pieces obligatoires sont presentes (${required.joinToString(", ") { it.second }})"
                    else -> "Manquant: ${missing.joinToString(", ") { it.second }}"
                }
            )
        }

        val facture = ctx.facture
        val allFactures = ctx.allFactures
        val bc = ctx.bc
        val op = ctx.op
        val contrat = ctx.contrat
        val checklist = ctx.checklist
        val tableau = ctx.tableau
        val pv = ctx.pv
        val arf = ctx.arf

        val fDoc = facture?.document
        val bcDoc = bc?.document

        val fTtc = facture?.montantTtc ?: docAmount(fDoc, "montantTTC")
        val fHt = facture?.montantHt ?: docAmount(fDoc, "montantHT")
        val fTva = facture?.montantTva ?: docAmount(fDoc, "montantTVA")
        val bcTtc = bc?.montantTtc ?: docAmount(bcDoc, "montantTTC")
        val bcHt = bc?.montantHt ?: docAmount(bcDoc, "montantHT")
        val bcTva = bc?.montantTva ?: docAmount(bcDoc, "montantTVA")

        if (isEnabled("R01") && dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontantWithFraction("R01", "Concordance montant TTC : Facture = BC",
                fTtc, bcTtc, tol, dossier,
                listOf(
                    evidence("trouve", "montantTTC", "Montant TTC de la facture", fDoc, fTtc),
                    evidence("attendu", "montantTTC", "Montant TTC du bon de commande", bcDoc, bcTtc)
                ))
        }

        if (isEnabled("R02") && dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontantWithFraction("R02", "Concordance montant HT : Facture = BC",
                fHt, bcHt, tol, dossier,
                listOf(
                    evidence("trouve", "montantHT", "Montant HT de la facture", fDoc, fHt),
                    evidence("attendu", "montantHT", "Montant HT du bon de commande", bcDoc, bcHt)
                ))
        }

        if (isEnabled("R03") && dossier.type == DossierType.BC && facture != null && bc != null) {
            results += checkMontantWithFraction("R03", "Concordance TVA : Facture = BC",
                fTva, bcTva, tol, dossier,
                listOf(
                    evidence("trouve", "montantTVA", "Montant TVA de la facture", fDoc, fTva),
                    evidence("attendu", "montantTVA", "Montant TVA du bon de commande", bcDoc, bcTva)
                ))
        }

        if (isEnabled("R03b") && dossier.type == DossierType.BC && facture != null && bc != null) {
            val fTauxTva = facture.tauxTva ?: docAmount(fDoc, "tauxTVA")
            val bcTauxTva = bc.tauxTva ?: docAmount(bcDoc, "tauxTVA")
            if (fTauxTva != null && bcTauxTva != null && fTauxTva.compareTo(bcTauxTva) != 0) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R03b",
                    libelle = "Taux TVA different entre Facture et BC (multi-taux possible)",
                    statut = StatutCheck.AVERTISSEMENT,
                    detail = "Facture: ${fTauxTva}%, BC: ${bcTauxTva}%",
                    valeurAttendue = bcTauxTva.toPlainString(), valeurTrouvee = fTauxTva.toPlainString(),
                    evidences = listOf(
                        evidence("trouve", "tauxTVA", "Taux TVA de la facture", fDoc, fTauxTva),
                        evidence("attendu", "tauxTVA", "Taux TVA du bon de commande", bcDoc, bcTauxTva)
                    )
                )
            }
        }

        // R30 : taux TVA Maroc dans la liste legale (CGI 2026 art. 87-100).
        //   0%   = exoneration (art. 91)
        //   7%   = produits de premiere necessite (art. 99-I)
        //   10%  = restauration / hotellerie / transport (art. 99-II)
        //   14%  = beurre / electricite / energie (art. 99-III)
        //   20%  = taux normal (art. 88)
        // Tout autre taux est anormal : soit erreur d'extraction, soit
        // facture frauduleuse cherchant a minorer la TVA collectee.
        if (isEnabled("R30") && facture != null) {
            val fTauxTva = facture.tauxTva ?: docAmount(fDoc, "tauxTVA")
            if (fTauxTva != null) {
                val tauxLegaux = listOf(BigDecimal.ZERO, BigDecimal("7"), BigDecimal("10"), BigDecimal("14"), BigDecimal("20"))
                val ok = tauxLegaux.any { it.compareTo(fTauxTva) == 0 }
                results += ResultatValidation(
                    dossier = dossier, regle = "R30",
                    libelle = "Taux TVA conforme aux taux legaux marocains (CGI art. 87-100)",
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                    detail = if (ok)
                        "Taux ${fTauxTva}% present dans la liste legale {0, 7, 10, 14, 20}"
                    else
                        "Taux ${fTauxTva}% hors liste legale CGI : verifier la facture (erreur d'extraction ou taux frauduleux)",
                    valeurAttendue = "0 / 7 / 10 / 14 / 20",
                    valeurTrouvee = fTauxTva.toPlainString(),
                    evidences = listOf(
                        evidence("source", "tauxTVA", "Taux TVA de la facture", fDoc, fTauxTva)
                    )
                )
            }
        }

        val opDoc = op?.document
        val arfDoc = arf?.document
        val tcDoc = dossier.documents.find { it.typeDocument == TypeDocument.TABLEAU_CONTROLE }
        val ckDoc = dossier.documents.find { it.typeDocument == TypeDocument.CHECKLIST_AUTOCONTROLE }
        val opMontant = op?.montantOperation ?: docAmount(opDoc, "montantOperation", "montantBrut")

        if (isEnabled("R04") && facture != null && op != null && op.retenues.isEmpty()) {
            results += checkMontant("R04", "Montant OP = TTC facture (sans retenues)",
                opMontant, fTtc, tol, dossier,
                listOf(
                    evidence("trouve", "montantOperation", "Montant de l'ordre de paiement", opDoc, opMontant),
                    evidence("attendu", "montantTTC", "Montant TTC de la facture", fDoc, fTtc)
                ))
        }

        if (isEnabled("R05") && facture != null && op != null && op.retenues.isNotEmpty()) {
            val totalRetenues = op.retenues.mapNotNull { it.montant }.fold(BigDecimal.ZERO) { acc, m -> acc.add(m) }
            val attendu = fTtc?.subtract(totalRetenues)
            results += checkMontant("R05", "Montant OP = TTC - retenues",
                opMontant, attendu, tol, dossier,
                listOf(
                    evidence("trouve", "montantOperation", "Montant net de l'OP", opDoc, opMontant),
                    evidence("source", "montantTTC", "TTC facture", fDoc, fTtc),
                    evidence("source", "retenues", "Total retenues", opDoc, totalRetenues),
                    evidence("calcule", "montantAttendu", "TTC - retenues", null, attendu)
                ))
        }

        if (isEnabled("R06") && op != null) {
            for (ret in op.retenues) {
                if (ret.base != null && ret.taux != null && ret.montant != null) {
                    val calcule = ret.base!!.multiply(ret.taux).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
                    val ok = (calcule.subtract(ret.montant).abs()) <= tol
                    results += ResultatValidation(
                        dossier = dossier, regle = "R06",
                        libelle = "Calcul retenue ${ret.type} : base x taux = montant",
                        statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                        detail = "${ret.base} x ${ret.taux}% = ${calcule} (trouve: ${ret.montant})",
                        valeurAttendue = calcule.toPlainString(), valeurTrouvee = ret.montant?.toPlainString(),
                        evidences = listOf(
                            evidence("source", "base", "Base de la retenue ${ret.type}", opDoc, ret.base),
                            evidence("source", "taux", "Taux de la retenue ${ret.type}", opDoc, ret.taux),
                            evidence("calcule", "montantAttendu", "base × taux", null, calcule),
                            evidence("trouve", "montant", "Montant retenue", opDoc, ret.montant)
                        )
                    )
                }
            }
        }

        // R06b : conformite des taux de retenue a la source au Code General
        // des Impots marocain. R06 verifie l'arithmetique base*taux=montant ;
        // R06b verifie que le taux declare correspond au taux legal :
        //   - TVA_SOURCE   = 75 % (CGI art. 117 : retenue TVA marches publics)
        //   - IS_HONORAIRES = 10 % (CGI art. 73-II-G : retenue IR honoraires)
        // Pour GARANTIE / AUTRE : pas de taux legal universel (depend du contrat),
        // donc pas de check (on laisse a R-M/R-C pour les engagements typees).
        if (isEnabled("R06b") && op != null) {
            for (ret in op.retenues) {
                val tauxAttendu = when (ret.type) {
                    TypeRetenue.TVA_SOURCE -> BigDecimal("75")
                    TypeRetenue.IS_HONORAIRES -> BigDecimal("10")
                    else -> null
                }
                if (tauxAttendu != null && ret.taux != null) {
                    val ok = ret.taux!!.subtract(tauxAttendu).abs() <= BigDecimal("0.5")
                    val sourceArt = when (ret.type) {
                        TypeRetenue.TVA_SOURCE -> "CGI art. 117"
                        TypeRetenue.IS_HONORAIRES -> "CGI art. 73-II-G"
                        else -> "CGI"
                    }
                    results += ResultatValidation(
                        dossier = dossier, regle = "R06b",
                        libelle = "Taux retenue ${ret.type} conforme au CGI (${sourceArt})",
                        statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                        detail = if (ok)
                            "Taux ${ret.taux}% conforme au taux legal ${tauxAttendu}% (${sourceArt})"
                        else
                            "Taux declare ${ret.taux}% != taux legal ${tauxAttendu}% (${sourceArt}) — verifier la retenue",
                        valeurAttendue = "${tauxAttendu}%",
                        valeurTrouvee = "${ret.taux}%",
                        evidences = listOf(
                            evidence("source", "taux", "Taux de la retenue ${ret.type}", opDoc, ret.taux),
                            evidence("attendu", "tauxLegal", "Taux legal ${sourceArt}", null, tauxAttendu)
                        )
                    )
                }
            }
        }

        if (isEnabled("R07") && facture != null && op != null) {
            val fNumero = facture.numeroFacture ?: docStr(fDoc, "numeroFacture")
            val opRefFacture = op.referenceFacture ?: docStr(opDoc, "referenceFacture")
            val ok = matchReference(opRefFacture, fNumero)
            results += ResultatValidation(
                dossier = dossier, regle = "R07",
                libelle = "Reference facture citee dans l'OP",
                statut = if (ok) StatutCheck.CONFORME else if (opRefFacture == null) StatutCheck.AVERTISSEMENT else StatutCheck.NON_CONFORME,
                valeurAttendue = fNumero, valeurTrouvee = opRefFacture,
                evidences = listOf(
                    evidence("attendu", "numeroFacture", "Numero de la facture", fDoc, fNumero),
                    evidence("trouve", "referenceFacture", "Reference facture dans l'OP", opDoc, opRefFacture)
                )
            )
        }

        if (isEnabled("R08") && op != null) {
            val refAttendue = bc?.reference ?: docStr(bcDoc, "reference") ?: contrat?.referenceContrat ?: docStr(contrat?.document, "referenceContrat")
            val refDoc = bcDoc ?: contrat?.document
            val opRefBc = op.referenceBcOuContrat ?: docStr(opDoc, "referenceBcOuContrat")
            if (refAttendue != null) {
                val ok = matchReference(opRefBc, refAttendue)
                results += ResultatValidation(
                    dossier = dossier, regle = "R08",
                    libelle = "Reference BC/Contrat citee dans l'OP",
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                    valeurAttendue = refAttendue, valeurTrouvee = opRefBc,
                    evidences = listOf(
                        evidence("attendu", "reference", "Reference BC/Contrat source", refDoc, refAttendue),
                        evidence("trouve", "referenceBcOuContrat", "Reference citee dans l'OP", opDoc, opRefBc)
                    )
                )
            }
        }

        if (isEnabled("R09")) {
            val iceFacture = (facture?.ice ?: docStr(fDoc, "ice"))?.trim()?.takeIf { it.isNotBlank() }
            val iceArf = (arf?.ice ?: docStr(arfDoc, "ice"))?.trim()?.takeIf { it.isNotBlank() }
            // BC et OP n'ont pas de champ ice typee (legacy schema), mais les
            // documents extraits peuvent porter un ICE dans donneesExtraites.
            // Si present, il DOIT correspondre a la facture sinon c'est une
            // substitution de fournisseur (cas frequent de fraude).
            val iceBc = docStr(bcDoc, "ice")?.trim()?.takeIf { it.isNotBlank() }
            val iceOp = docStr(opDoc, "ice")?.trim()?.takeIf { it.isNotBlank() }
            // ICE = 15 chiffres significatifs (decret 2-11-13 OMPIC). On NE retire
            // PAS les zeros de tete : `001509176000008` est l'ICE legal d'une
            // entreprise, distinct de `1509176000008` (13 chiffres = ICE invalide
            // ou autre entreprise). `normalizeId` aurait fait matcher les deux.
            val allIces = listOfNotNull(iceFacture, iceArf, iceBc, iceOp)
            val normalizedIces = allIces.mapNotNull { normalizeIce(it) }.distinct()
            val statut = when {
                facture == null && arf == null && iceBc == null && iceOp == null -> StatutCheck.AVERTISSEMENT
                facture != null && iceFacture == null -> StatutCheck.NON_CONFORME
                arf != null && iceArf == null && facture == null -> StatutCheck.AVERTISSEMENT
                arf != null && iceArf == null -> StatutCheck.NON_CONFORME
                normalizedIces.size > 1 -> StatutCheck.NON_CONFORME
                else -> StatutCheck.CONFORME
            }
            val sources = buildList {
                if (iceFacture != null) add("facture=$iceFacture")
                if (iceArf != null) add("attestation=$iceArf")
                if (iceBc != null) add("BC=$iceBc")
                if (iceOp != null) add("OP=$iceOp")
            }
            results += ResultatValidation(
                dossier = dossier, regle = "R09",
                libelle = "Coherence ICE fournisseur entre documents",
                statut = statut,
                valeurAttendue = iceFacture ?: "Absent de la facture",
                valeurTrouvee = listOfNotNull(iceArf, iceBc, iceOp).firstOrNull() ?: "Absent",
                detail = when (statut) {
                    StatutCheck.AVERTISSEMENT -> "ICE non disponible (sources documentaires insuffisantes)"
                    StatutCheck.NON_CONFORME -> when {
                        facture != null && iceFacture == null -> "ICE manquant sur la facture (obligation B2B Maroc)"
                        arf != null && iceArf == null -> "ICE manquant sur l'attestation fiscale alors que la facture en mentionne un ($iceFacture)"
                        normalizedIces.size > 1 -> "ICE differents entre documents : ${sources.joinToString(", ")}"
                        else -> "ICE incoherent : ${sources.joinToString(", ")}"
                    }
                    else -> "ICE identiques (${sources.joinToString(", ")})"
                },
                evidences = listOfNotNull(
                    evidence("source", "ice", "ICE sur la facture", fDoc, iceFacture),
                    evidence("source", "ice", "ICE sur l'attestation fiscale", arfDoc, iceArf),
                    iceBc?.let { evidence("source", "ice", "ICE sur le bon de commande", bcDoc, it) },
                    iceOp?.let { evidence("source", "ice", "ICE sur l'ordre de paiement", opDoc, it) }
                )
            )
        }

        // R09b : validite du format ICE (decret 2-11-13 + arrete OMPIC).
        // Un ICE doit faire exactement 15 chiffres. Un ICE plus court (ex:
        // perte de zeros initiaux par OCR) ou plus long est anormal et doit
        // bloquer le dossier — la regle de coherence R09 ne suffit pas car
        // deux ICE tronques identiques se "matcheraient" silencieusement.
        if (isEnabled("R09b")) {
            val iceFacture = (facture?.ice ?: docStr(fDoc, "ice"))?.trim()?.takeIf { it.isNotBlank() }
            val iceArf = (arf?.ice ?: docStr(arfDoc, "ice"))?.trim()?.takeIf { it.isNotBlank() }
            val invalides = mutableListOf<String>()
            val r09bEvidences = mutableListOf<ValidationEvidence>()
            if (iceFacture != null && !isIceFormatValid(iceFacture)) {
                invalides += "ICE facture invalide : '$iceFacture' (attendu 15 chiffres)"
                r09bEvidences += evidence("source", "ice", "ICE sur la facture", fDoc, iceFacture)
            }
            if (iceArf != null && !isIceFormatValid(iceArf)) {
                invalides += "ICE attestation fiscale invalide : '$iceArf' (attendu 15 chiffres)"
                r09bEvidences += evidence("source", "ice", "ICE sur l'attestation fiscale", arfDoc, iceArf)
            }
            // Ne lever de resultat que si au moins un ICE etait extrait : sinon
            // R09 a deja signale l'absence et on ne veut pas du bruit redondant.
            if (iceFacture != null || iceArf != null) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R09b",
                    libelle = "Format ICE valide (15 chiffres exacts)",
                    statut = if (invalides.isEmpty()) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                    detail = if (invalides.isEmpty())
                        "ICE au format attendu (15 chiffres)"
                    else
                        invalides.joinToString(" | "),
                    evidences = r09bEvidences.ifEmpty { null }
                )
            }
        }

        if (isEnabled("R10")) {
            val ifFacture = (facture?.identifiantFiscal ?: docStr(fDoc, "identifiantFiscal"))?.trim()?.takeIf { it.isNotBlank() }
            val ifArf = (arf?.identifiantFiscal ?: docStr(arfDoc, "identifiantFiscal"))?.trim()?.takeIf { it.isNotBlank() }
            val normalizedIfs = listOfNotNull(ifFacture, ifArf).mapNotNull { normalizeId(it) }.distinct()
            val statut = when {
                facture == null && arf == null -> StatutCheck.AVERTISSEMENT
                facture != null && ifFacture == null -> StatutCheck.NON_CONFORME
                arf != null && ifArf == null && facture == null -> StatutCheck.AVERTISSEMENT
                arf != null && ifArf == null -> StatutCheck.NON_CONFORME
                ifFacture != null && ifArf != null && normalizedIfs.size != 1 -> StatutCheck.NON_CONFORME
                else -> StatutCheck.CONFORME
            }
            results += ResultatValidation(
                dossier = dossier, regle = "R10",
                libelle = "Coherence IF fournisseur entre documents",
                statut = statut,
                valeurAttendue = ifFacture ?: "Absent de la facture",
                valeurTrouvee = ifArf ?: "Absent de l'attestation fiscale",
                detail = when (statut) {
                    StatutCheck.AVERTISSEMENT -> "IF non disponible (sources documentaires insuffisantes)"
                    StatutCheck.NON_CONFORME -> when {
                        facture != null && ifFacture == null -> "IF manquant sur la facture"
                        arf != null && ifArf == null -> "IF manquant sur l'attestation fiscale alors que la facture en mentionne un ($ifFacture)"
                        else -> "IF differents : facture=$ifFacture, attestation=$ifArf"
                    }
                    else -> "IF identiques"
                },
                evidences = listOf(
                    evidence("source", "identifiantFiscal", "IF sur la facture", fDoc, ifFacture),
                    evidence("source", "identifiantFiscal", "IF sur l'attestation fiscale", arfDoc, ifArf)
                )
            )
        }

        if (isEnabled("R11") && allFactures.isNotEmpty() && op != null) {
            val allFactureRibs = allFactures.mapNotNull { f ->
                normalizeRib(f.rib ?: docStr(f.document, "rib"))
            }.distinct()
            val allFactureRibsFromJson = allFactures.mapNotNull { f ->
                @Suppress("UNCHECKED_CAST")
                (f.document.donneesExtraites?.get("ribs") as? List<String>)
            }.flatten().mapNotNull { normalizeRib(it) }.distinct()
            val combinedFactureRibs = (allFactureRibs + allFactureRibsFromJson).distinct()

            val opRibEntity = normalizeRib(op.rib ?: docStr(opDoc, "rib"))
            val opRibs = listOfNotNull(opRibEntity) +
                ((@Suppress("UNCHECKED_CAST") (op.document.donneesExtraites?.get("ribs") as? List<String>))
                    ?.mapNotNull { normalizeRib(it) } ?: emptyList())
            val allOpRibs = opRibs.distinct()

            // Schema OP : `rib` + `ribs[]` sont des RIBs de BENEFICIAIRE.
            // Verite metier : chaque RIB liste sur l'OP doit etre un RIB
            // legitime du fournisseur (donc apparaitre dans les RIBs des
            // factures). Un RIB OP supplementaire et inconnu = potentiellement
            // un compte attaquant glisse dans l'OP -> R11 doit lever
            // NON_CONFORME meme si un autre RIB matche par ailleurs. L'ancien
            // critere `any { fr == or }` etait trop laxiste : il ignorait les
            // RIBs OP frauduleux des qu'un seul des RIBs OP correspondait.
            val unknownOpRibs = if (combinedFactureRibs.isEmpty()) emptyList()
                else allOpRibs.filterNot { or -> or in combinedFactureRibs }
            val hasMatch = combinedFactureRibs.any { fr -> allOpRibs.any { or -> fr == or } }
            val docIds = (allFactures.mapNotNull { it.document.id?.toString() } + listOfNotNull(op.document.id?.toString())).distinct()

            val r11Evidences = mutableListOf<ValidationEvidence>()
            allFactures.forEach { f ->
                val rib = normalizeRib(f.rib ?: docStr(f.document, "rib"))
                if (rib != null) r11Evidences += evidence("source", "rib", "RIB de la facture", f.document, rib)
            }
            if (allOpRibs.isNotEmpty()) r11Evidences += evidence("trouve", "rib", "RIB de l'ordre de paiement", opDoc, allOpRibs.joinToString(", "))
            results += ResultatValidation(
                dossier = dossier, regle = "R11",
                libelle = "Coherence RIB : Factures ↔ OP",
                statut = when {
                    combinedFactureRibs.isEmpty() || allOpRibs.isEmpty() -> StatutCheck.AVERTISSEMENT
                    !hasMatch -> StatutCheck.NON_CONFORME
                    unknownOpRibs.isNotEmpty() -> StatutCheck.NON_CONFORME
                    else -> StatutCheck.CONFORME
                },
                valeurAttendue = "Factures: ${combinedFactureRibs.joinToString(", ").ifBlank { "Aucun RIB" }}",
                valeurTrouvee = "OP: ${allOpRibs.joinToString(", ").ifBlank { "Aucun RIB" }}",
                detail = when {
                    !hasMatch && combinedFactureRibs.isNotEmpty() && allOpRibs.isNotEmpty() ->
                        "Aucun RIB de l'OP ne correspond aux RIBs des factures (substitution potentielle de beneficiaire)"
                    unknownOpRibs.isNotEmpty() ->
                        "RIB(s) OP non reconnu(s) dans les factures: ${unknownOpRibs.joinToString(", ")} (compte additionnel suspect)"
                    combinedFactureRibs.size > 1 -> "${combinedFactureRibs.size} RIBs trouves dans les factures"
                    else -> null
                },
                documentIds = docIds.joinToString(","),
                evidences = r11Evidences.ifEmpty { null }
            )
        }

        if (isEnabled("R14")) {
            val r14Evidences = mutableListOf<ValidationEvidence>()
            allFactures.forEach { f ->
                val v = f.fournisseur ?: docStr(f.document, "fournisseur")
                if (v != null) r14Evidences += evidence("source", "fournisseur", "Fournisseur facture", f.document, v)
            }
            val bcFourn = bc?.fournisseur ?: docStr(bcDoc, "fournisseur")
            if (bcFourn != null) r14Evidences += evidence("source", "fournisseur", "Fournisseur du BC", bcDoc, bcFourn)
            val opBenef = op?.beneficiaire ?: docStr(opDoc, "beneficiaire")
            if (opBenef != null) r14Evidences += evidence("source", "beneficiaire", "Beneficiaire de l'OP", opDoc, opBenef)
            val tcFourn = tableau?.fournisseur ?: docStr(tcDoc, "fournisseur")
            if (tcFourn != null) r14Evidences += evidence("source", "fournisseur", "Fournisseur du TC", tcDoc, tcFourn)
            val ckPrest = checklist?.prestataire ?: docStr(ckDoc, "prestataire")
            if (ckPrest != null) r14Evidences += evidence("source", "prestataire", "Prestataire checklist", ckDoc, ckPrest)
            val arfRaison = arf?.raisonSociale ?: docStr(arfDoc, "raisonSociale")
            if (arfRaison != null) r14Evidences += evidence("source", "raisonSociale", "Raison sociale attestation fiscale", arfDoc, arfRaison)

            val canonIds = mutableSetOf<String>()
            allFactures.forEach { f -> f.fournisseurCanonique?.id?.toString()?.let { canonIds += "CANON:$it" } }
            bc?.fournisseurCanonique?.id?.toString()?.let { canonIds += "CANON:$it" }
            contrat?.fournisseurCanonique?.id?.toString()?.let { canonIds += "CANON:$it" }
            arf?.fournisseurCanonique?.id?.toString()?.let { canonIds += "CANON:$it" }

            val rawFallback = mutableListOf<String>()
            if (bc?.fournisseurCanonique == null) bcFourn?.let { rawFallback += it }
            if (arf?.fournisseurCanonique == null) arfRaison?.let { rawFallback += it }
            opBenef?.let { rawFallback += it }
            tcFourn?.let { rawFallback += it }
            ckPrest?.let { rawFallback += it }
            dossier.fournisseur?.let { rawFallback += it }
            allFactures.forEach { f ->
                if (f.fournisseurCanonique == null) {
                    (f.fournisseur ?: docStr(f.document, "fournisseur"))?.let { rawFallback += it }
                }
            }
            val normalizedFallback = rawFallback.map { fournisseurMatchingService.normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()

            val allKeys = canonIds + normalizedFallback.map { "NORM:$it" }
            if (allKeys.size > 1) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R14",
                    libelle = "Coherence fournisseur entre documents",
                    statut = StatutCheck.AVERTISSEMENT,
                    detail = "Fournisseurs distincts detectes apres normalisation: ${allKeys.size} entites differentes",
                    evidences = r14Evidences.ifEmpty { null }
                )
            } else if (allKeys.isNotEmpty()) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R14",
                    libelle = "Coherence fournisseur entre documents",
                    statut = StatutCheck.CONFORME,
                    detail = "Fournisseur unique (via normalisation / canonical)",
                    evidences = r14Evidences.ifEmpty { null }
                )
            }
        }

        // R14b: attestation fiscale non conforme si raison sociale / ICE / IF different de la facture
        if (isEnabled("R14b") && facture != null && arf != null) {
            val fRaison = (facture.fournisseur ?: docStr(fDoc, "fournisseur"))?.trim()?.takeIf { it.isNotBlank() }
            val arfRaison = (arf.raisonSociale ?: docStr(arfDoc, "raisonSociale"))?.trim()?.takeIf { it.isNotBlank() }
            val fIce = (facture.ice ?: docStr(fDoc, "ice"))?.trim()?.takeIf { it.isNotBlank() }
            val arfIce = (arf.ice ?: docStr(arfDoc, "ice"))?.trim()?.takeIf { it.isNotBlank() }
            val fIf = (facture.identifiantFiscal ?: docStr(fDoc, "identifiantFiscal"))?.trim()?.takeIf { it.isNotBlank() }
            val arfIf = (arf.identifiantFiscal ?: docStr(arfDoc, "identifiantFiscal"))?.trim()?.takeIf { it.isNotBlank() }

            val mismatches = mutableListOf<String>()
            val r14bEvidences = mutableListOf<ValidationEvidence>()

            if (fRaison != null && arfRaison != null) {
                val sim = labelSimilarity(fRaison, arfRaison)
                if (sim < 0.70) {
                    mismatches += "Raison sociale : facture « $fRaison » ≠ attestation « $arfRaison » (similarite ${"%.0f".format(sim * 100)}%)"
                }
            }
            if (fIce != null && arfIce != null && normalizeIce(fIce) != normalizeIce(arfIce)) {
                mismatches += "ICE : facture=$fIce, attestation=$arfIce"
            }
            if (fIf != null && arfIf != null && normalizeId(fIf) != normalizeId(arfIf)) {
                mismatches += "IF : facture=$fIf, attestation=$arfIf"
            }

            r14bEvidences += evidence("source", "fournisseur", "Fournisseur facture", fDoc, fRaison)
            r14bEvidences += evidence("source", "raisonSociale", "Raison sociale attestation fiscale", arfDoc, arfRaison)
            if (fIce != null) r14bEvidences += evidence("source", "ice", "ICE facture", fDoc, fIce)
            if (arfIce != null) r14bEvidences += evidence("source", "ice", "ICE attestation fiscale", arfDoc, arfIce)
            if (fIf != null) r14bEvidences += evidence("source", "identifiantFiscal", "IF facture", fDoc, fIf)
            if (arfIf != null) r14bEvidences += evidence("source", "identifiantFiscal", "IF attestation fiscale", arfDoc, arfIf)

            val missingData = fRaison == null || arfRaison == null
            val statut = when {
                mismatches.isNotEmpty() -> StatutCheck.NON_CONFORME
                missingData -> StatutCheck.AVERTISSEMENT
                else -> StatutCheck.CONFORME
            }
            val detail = when {
                mismatches.isNotEmpty() -> "Attestation fiscale non conforme au fournisseur : " + mismatches.joinToString(" | ")
                missingData -> "Donnees fournisseur incompletes pour comparer (facture=${fRaison ?: "?"}, attestation=${arfRaison ?: "?"})"
                else -> "Attestation appartient bien au fournisseur « ${arfRaison ?: fRaison} »"
            }

            results += ResultatValidation(
                dossier = dossier, regle = "R14b",
                libelle = "Attestation fiscale conforme au fournisseur de la facture",
                statut = statut,
                detail = detail,
                valeurAttendue = fRaison,
                valeurTrouvee = arfRaison,
                evidences = r14bEvidences.ifEmpty { null }
            )
        }

        if (isEnabled("R21") && allFactures.isNotEmpty()) {
            measureRule("R21", results) {
                val antiDoublonTolPct = BigDecimal(antiDoublonMontantTolerancePct)
                val today = LocalDate.now()
                val lookbackFrom = today.minusMonths(antiDoublonLookbackMonths)
                val dossierId = dossier.id!!
                for (f in allFactures) {
                    val doublons = mutableListOf<String>()
                    val compensations = mutableListOf<String>()
                    val r21Evidences = mutableListOf<ValidationEvidence>()

                    val numero = f.numeroFacture?.takeIf { it.isNotBlank() }
                    val isCurrentAvoir = isFactureAvoir(f)

                    if (numero != null) {
                        val byNumero = factureRepository.findByNumeroFacture(numero, lookbackFrom, dossierId)
                        byNumero.forEach { d ->
                            val dIsAvoir = isFactureAvoir(d)
                            // Une compensation legitime : l'un des deux est un avoir
                            // (libelle AVOIR/ANNUL/RECTIF ou montant TTC negatif).
                            if (isCurrentAvoir != dIsAvoir) {
                                compensations += "Compensation : '${numero}' lie a un avoir dans dossier ${d.dossier.reference}"
                                r21Evidences += evidence("compensation", "numeroFacture",
                                    "Avoir/compensation - dossier ${d.dossier.reference}", f.document, numero)
                            } else {
                                doublons += "Meme numero '${numero}' dans dossier ${d.dossier.reference}" +
                                    (d.dateFacture?.let { " du ${it}" } ?: "")
                                r21Evidences += evidence("doublon", "numeroFacture",
                                    "Doublon par numero - dossier ${d.dossier.reference}", f.document, numero)
                            }
                        }
                    }

                    // Detection intra-dossier : les requetes repo excluent le
                    // dossier courant (excludeDossierId) donc deux factures
                    // identiques chargees dans le MEME dossier (cas frequent :
                    // re-upload sans remplacement) ne sont jamais comparees.
                    // On compare manuellement aux autres factures du dossier.
                    val others = allFactures.filter { it !== f }
                    if (numero != null) {
                        others.filter { it.numeroFacture?.equals(numero, ignoreCase = true) == true }
                            .forEach { o ->
                                val oIsAvoir = isFactureAvoir(o)
                                if (isCurrentAvoir != oIsAvoir) {
                                    compensations += "Compensation intra-dossier : '${numero}' lie a un avoir"
                                    r21Evidences += evidence("compensation", "numeroFacture",
                                        "Avoir/compensation intra-dossier", f.document, numero)
                                } else {
                                    doublons += "Doublon intra-dossier : meme numero '${numero}'" +
                                        (o.dateFacture?.let { " du ${it}" } ?: "")
                                    r21Evidences += evidence("doublon", "numeroFacture",
                                        "Doublon intra-dossier par numero", f.document, numero)
                                }
                            }
                    }

                    val ttc = f.montantTtc
                    val date = f.dateFacture
                    val canonId = f.fournisseurCanonique?.id
                    val fournisseur = f.fournisseur?.takeIf { it.isNotBlank() }
                    // Le combo fournisseur+montant+date n'a de sens que sur des
                    // factures non-avoir et avec un TTC strictement positif :
                    // sinon la fenetre BETWEEN se centre sur 0 ou un montant
                    // negatif et matche tout ce qui flotte autour.
                    if (ttc != null && date != null && !isCurrentAvoir && ttc.signum() > 0) {
                        val delta = ttc.multiply(antiDoublonTolPct)
                        val mMin = ttc.subtract(delta)
                        val mMax = ttc.add(delta)
                        val dMin = date.minusDays(antiDoublonDateToleranceDays)
                        val dMax = date.plusDays(antiDoublonDateToleranceDays)

                        val byCombo = if (canonId != null) {
                            factureRepository.findByMontantCanoniqueDate(canonId, mMin, mMax, dMin, dMax, dossierId)
                        } else if (fournisseur != null) {
                            factureRepository.findByMontantFournisseurDate(fournisseur, mMin, mMax, dMin, dMax, dossierId)
                        } else emptyList()

                        byCombo
                            .filter { it.numeroFacture?.equals(numero, ignoreCase = true) != true }
                            .filter { !isFactureAvoir(it) } // exclure les avoirs/compensations
                            .forEach { d ->
                                val via = if (canonId != null) "meme fournisseur canonique" else "meme fournisseur"
                                doublons += "${via}, montant/date (+/- ${antiDoublonDateToleranceDays}j) dans dossier ${d.dossier.reference}"
                                r21Evidences += evidence("doublon", "combo",
                                    "Doublon fournisseur+montant+date - dossier ${d.dossier.reference}",
                                    f.document, "${fournisseur ?: f.fournisseurCanonique?.nomCanonique} / ${ttc} / ${date}")
                            }

                        // Combo intra-dossier (meme fournisseur + montant proche + date proche).
                        others
                            .filter { it.numeroFacture?.equals(numero, ignoreCase = true) != true }
                            .filter { !isFactureAvoir(it) }
                            .filter { o ->
                                val oTtc = o.montantTtc ?: return@filter false
                                val oDate = o.dateFacture ?: return@filter false
                                val oCanon = o.fournisseurCanonique?.id
                                val sameFournisseur = when {
                                    canonId != null && oCanon != null -> canonId == oCanon
                                    fournisseur != null -> o.fournisseur?.equals(fournisseur, ignoreCase = true) == true
                                    else -> false
                                }
                                sameFournisseur && oTtc in mMin..mMax &&
                                    !oDate.isBefore(dMin) && !oDate.isAfter(dMax)
                            }
                            .forEach { o ->
                                doublons += "Doublon intra-dossier : meme fournisseur, montant/date (+/- ${antiDoublonDateToleranceDays}j)"
                                r21Evidences += evidence("doublon", "combo",
                                    "Doublon intra-dossier fournisseur+montant+date",
                                    f.document, "${fournisseur ?: f.fournisseurCanonique?.nomCanonique} / ${ttc} / ${date}")
                            }
                    }

                    when {
                        doublons.isNotEmpty() -> {
                            val detail = doublons.joinToString(" | ") +
                                if (compensations.isNotEmpty()) " | (compensations ignorees : ${compensations.size})" else ""
                            results += ResultatValidation(
                                dossier = dossier, regle = "R21",
                                libelle = "Anti-doublon facture (${antiDoublonLookbackMonths} mois glissants)",
                                statut = StatutCheck.NON_CONFORME,
                                detail = detail,
                                evidences = r21Evidences.ifEmpty { null }
                            )
                        }
                        compensations.isNotEmpty() -> {
                            results += ResultatValidation(
                                dossier = dossier, regle = "R21",
                                libelle = "Anti-doublon facture (${antiDoublonLookbackMonths} mois glissants)",
                                statut = StatutCheck.CONFORME,
                                detail = "Compensation/avoir detecte(s) (${compensations.size}), aucun doublon : " + compensations.joinToString(" | "),
                                evidences = r21Evidences.ifEmpty { null }
                            )
                        }
                        else -> {
                            results += ResultatValidation(
                                dossier = dossier, regle = "R21",
                                libelle = "Anti-doublon facture (${antiDoublonLookbackMonths} mois glissants)",
                                statut = StatutCheck.CONFORME,
                                detail = "Aucun doublon detecte pour la facture ${f.numeroFacture ?: "(sans numero)"}" +
                                    if (isCurrentAvoir) " (avoir/compensation, hors detection combo)" else ""
                            )
                        }
                    }
                }
            }
        }

        if (isEnabled("R22") && op != null && pv != null) {
            measureRule("R22", results) {
                // On utilise STRICTEMENT pv.dateReception (date a laquelle le service
                // a ete reconnu recu et l'attestation signee). Auparavant, le code
                // fallback sur pv.periodeFin si dateReception etait null — c'etait
                // dangereux : un PV trimestriel a periodeFin=30/06 mais signe le
                // 05/07 acceptait un OP date du 30/06 (paiement avant signature
                // effective du PV). Sans dateReception, R22 ne peut pas trancher
                // -> AVERTISSEMENT pour forcer une revue humaine plutot qu'un
                // CONFORME silencieux base sur une date de fin de periode.
                val dateReception = pv.dateReception
                val dateOp = op.dateEmission
                val opDocLocal = opDoc
                val pvDocLocal = pv.document
                when {
                    dateReception == null || dateOp == null -> {
                        val cause = when {
                            dateOp == null && dateReception == null -> "date OP et date de reception du PV manquantes"
                            dateOp == null -> "date d'emission de l'OP manquante"
                            else -> "date de reception du PV manquante (periodeFin n'est pas un substitut fiable : un PV trimestriel peut etre signe apres sa periode)"
                        }
                        results += ResultatValidation(
                            dossier = dossier, regle = "R22",
                            libelle = "Paiement posterieur a la reception",
                            statut = StatutCheck.AVERTISSEMENT,
                            detail = "Impossible de verifier R22 : $cause"
                        )
                    }
                    dateOp.isBefore(dateReception) -> {
                        results += ResultatValidation(
                            dossier = dossier, regle = "R22",
                            libelle = "Paiement posterieur a la reception",
                            statut = StatutCheck.NON_CONFORME,
                            detail = "OP emis le ${dateOp}, soit avant la reception du ${dateReception}. " +
                                "Un paiement ne peut pas preceder la reception de la prestation.",
                            valeurAttendue = ">= ${dateReception}",
                            valeurTrouvee = dateOp.toString(),
                            evidences = listOf(
                                evidence("trouve", "dateEmission", "Date d'emission de l'OP", opDocLocal, dateOp),
                                evidence("attendu", "dateReception", "Date de reception du PV", pvDocLocal, dateReception)
                            )
                        )
                    }
                    else -> {
                        results += ResultatValidation(
                            dossier = dossier, regle = "R22",
                            libelle = "Paiement posterieur a la reception",
                            statut = StatutCheck.CONFORME,
                            detail = "OP du ${dateOp} posterieur a la reception du ${dateReception}",
                            evidences = listOf(
                                evidence("trouve", "dateEmission", "Date d'emission de l'OP", opDocLocal, dateOp),
                                evidence("source", "dateReception", "Date de reception du PV", pvDocLocal, dateReception)
                            )
                        )
                    }
                }
            }
        }

        // R25 : delai legal de paiement <= 60 jours pour les marches publics
        // (decret 2-22-431 du 08/03/2023, art. 159). Le decompte court a partir
        // de la date de constatation du service fait (PV de reception). On
        // utilise dateReception PV en priorite, sinon dateFacture en repli.
        // S'applique uniquement quand l'engagement est un Marche public.
        if (isEnabled("R25")) {
            val isMarche = dossier.engagement?.typeEngagement() == com.madaef.recondoc.entity.engagement.TypeEngagement.MARCHE
            if (isMarche && op != null) {
                val dateOp = op.dateEmission ?: docStr(opDoc, "dateEmission")?.let { parseLocalDate(it) }
                // Source legale du decompte = "constatation du service fait"
                // (decret 2-22-431 art. 159). Priorite :
                //   1. facture.dateReceptionFacture (cachet d'arrivee MADAEF —
                //      depuis V35) : la plus precise legalement.
                //   2. PV de reception (date_reception ou periodeFin).
                //   3. dateFacture (fallback ancien dossier).
                val dateReception = facture?.dateReceptionFacture
                    ?: pv?.dateReception
                    ?: pv?.periodeFin
                    ?: facture?.dateFacture
                    ?: docStr(fDoc, "dateFacture")?.let { parseLocalDate(it) }
                val refSource = when {
                    facture?.dateReceptionFacture != null -> "cachet d'arrivee MADAEF"
                    pv?.dateReception != null -> "PV de reception"
                    pv?.periodeFin != null -> "fin de periode PV"
                    else -> "date facture (fallback)"
                }
                if (dateOp != null && dateReception != null) {
                    val jours = ChronoUnit.DAYS.between(dateReception, dateOp)
                    val ok = jours in 0..60
                    val source = "Decret 2-22-431 art. 159 (delai global de paiement marche public)"
                    results += ResultatValidation(
                        dossier = dossier, regle = "R25",
                        libelle = "Delai paiement marche public <= 60 jours",
                        statut = when {
                            ok -> StatutCheck.CONFORME
                            jours < 0 -> StatutCheck.NON_CONFORME
                            else -> StatutCheck.NON_CONFORME
                        },
                        detail = if (ok)
                            "OP emis ${jours} j apres la reception (${refSource}, limite 60 j). ${source}."
                        else if (jours < 0)
                            "OP du ${dateOp} anterieur a la reception du ${dateReception} (${refSource}, cf. R22). ${source}."
                        else
                            "Delai de paiement ${jours} jours (${refSource}), depasse le plafond legal de 60 jours. ${source}.",
                        valeurAttendue = "<= 60 jours",
                        valeurTrouvee = "${jours} jours",
                        evidences = listOfNotNull(
                            evidence("source", "dateReception", "Date constatation service fait (${refSource})",
                                if (facture?.dateReceptionFacture != null) fDoc else (pv?.document ?: fDoc), dateReception),
                            evidence("trouve", "dateEmission", "Date d'emission de l'OP", opDoc, dateOp)
                        )
                    )
                }
            }
        }

        // R26 : plafond legal de paiement en especes (CGI art. 193-ter +
        // Loi de finances 2019, maintenu en 2026). Tout paiement > 5 000 MAD
        // hors circuit bancaire est non deductible et expose le fournisseur
        // a une amende de 6 % (art. 193-ter §II). On detecte le paiement en
        // especes via op.natureOperation et op.donneesExtraites.modePaiement.
        if (isEnabled("R26") && op != null) {
            // Detection en 2 temps :
            //   1. champ type op.modePaiement (cf. PR #2d) : signal le plus
            //      fiable, vise par l'extraction structuree.
            //   2. fallback texte sur natureOperation + donneesExtraites pour
            //      les anciens dossiers (avant migration V35).
            val typedMode = op.modePaiement?.uppercase()
            val nature = (op.natureOperation ?: docStr(opDoc, "natureOperation") ?: "").lowercase()
            val modeText = (docStr(opDoc, "modePaiement") ?: "").lowercase()
            val isCash = typedMode == "ESPECES"
                || listOf("especes", "espèce", "espece", "cash", "comptant", "liquide")
                    .any { it in nature || it in modeText }
            val montant = op.montantOperation ?: docAmount(opDoc, "montantOperation", "montant")
            if (isCash && montant != null) {
                val plafond = BigDecimal("5000")
                val depasse = montant.compareTo(plafond) > 0
                results += ResultatValidation(
                    dossier = dossier, regle = "R26",
                    libelle = "Plafond paiement especes (CGI art. 193-ter)",
                    statut = if (depasse) StatutCheck.NON_CONFORME else StatutCheck.CONFORME,
                    detail = if (depasse)
                        "Paiement especes ${montant} MAD > plafond legal 5 000 MAD : non deductible (CGI art. 193-ter), amende 6 % encourue par le fournisseur."
                    else
                        "Paiement especes ${montant} MAD <= plafond 5 000 MAD",
                    valeurAttendue = "<= 5 000 MAD si especes",
                    valeurTrouvee = "${montant} MAD (especes)",
                    evidences = listOfNotNull(
                        evidence("source", "modePaiement", "Mode de reglement OP", opDoc, typedMode ?: op.natureOperation ?: nature),
                        evidence("source", "montantOperation", "Montant de l'OP", opDoc, montant)
                    )
                )
            }
        }

        // R27 : devise obligatoirement MAD (CGNC + Loi 9-88 art. 1).
        // Une facture marocaine doit etre libellee en MAD. Une facture en
        // EUR / USD sans contre-valeur MAD signalee est non conforme : risque
        // de comptabilisation au mauvais cours, contournement du controle des
        // changes Office des Changes. Lecture priorite :
        //   1. donneesExtraites.devise (champ explicite si extrait)
        //   2. detection texte EUR/USD/€/$ dans nature ou raison sociale
        //   3. defaut MAD si rien detecte (pas de signal d'alerte)
        if (isEnabled("R27") && facture != null) {
            // Lecture priorite :
            //   1. facture.devise (champ type, depuis V35) — signal le plus
            //      fiable, alimente par l'extraction Claude.
            //   2. donneesExtraites.devise (avant migration) — fallback.
            //   3. detection texte EUR/USD/€/$ dans les libelles libres.
            //   4. defaut MAD si rien detecte (pas d'alerte sur silence).
            val typedDevise = facture.devise?.trim()?.uppercase()
            val deviseFromJson = (docStr(fDoc, "devise", "currency") ?: "").trim().uppercase()
            val texte = listOfNotNull(
                docStr(fDoc, "natureOperation"),
                docStr(fDoc, "totalEnLettres"),
                docStr(fDoc, "mentionsLegales")
            ).joinToString(" ").uppercase()
            val deviseEffective = when {
                !typedDevise.isNullOrBlank() -> typedDevise
                deviseFromJson.isNotBlank() -> deviseFromJson
                "EUR" in texte || "EURO" in texte || "€" in texte -> "EUR"
                "USD" in texte || "DOLLAR" in texte || "$" in texte -> "USD"
                else -> "MAD" // par defaut suppose MAD (pas d'alerte sur silence)
            }
            val deviseValide = deviseEffective in setOf("MAD", "DH", "DHS", "DIRHAM", "DIRHAMS")
            val hasSignal = !typedDevise.isNullOrBlank() || deviseFromJson.isNotBlank()
            // Pas de check si on n'a aucun signal explicite : eviter le bruit.
            if (hasSignal || !deviseValide) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R27",
                    libelle = "Devise MAD obligatoire (CGNC + Loi 9-88)",
                    statut = if (deviseValide) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                    detail = if (deviseValide)
                        "Devise = ${deviseEffective}, conforme au CGNC."
                    else
                        "Devise detectee = ${deviseEffective}. Une facture marocaine doit etre en MAD (CGNC art. 1, Loi 9-88).",
                    valeurAttendue = "MAD",
                    valeurTrouvee = deviseEffective,
                    evidences = listOf(
                        evidence("source", "devise", "Devise extraite (champ type)", fDoc, deviseEffective)
                    )
                )
            }
        }

        // R31 : separation des pouvoirs ordonnateur / comptable
        // (decret 2-22-431 art. 21 + procedure CDG / MADAEF). Un OP doit etre
        // signe par DEUX personnes physiques distinctes — l'ordonnateur (qui
        // autorise la depense) et le comptable (qui execute). La meme personne
        // sur les deux roles = vice de procedure majeur (risque collusion,
        // controle interne defaillant).
        // S'applique des qu'au moins un signataire est extrait : sans aucun
        // signataire on n'emet rien (laisser R12 / completude documentaire
        // signaler le manquement).
        if (isEnabled("R31") && op != null) {
            val ord = op.signataireOrdonnateur?.trim()?.takeIf { it.isNotBlank() }
            val cpt = op.signataireComptable?.trim()?.takeIf { it.isNotBlank() }
            if (ord != null || cpt != null) {
                val r31Evidences = mutableListOf<ValidationEvidence>()
                if (ord != null) r31Evidences += evidence("source", "signataireOrdonnateur", "Ordonnateur", opDoc, ord)
                if (cpt != null) r31Evidences += evidence("source", "signataireComptable", "Comptable", opDoc, cpt)
                val statut: StatutCheck
                val detail: String
                when {
                    ord != null && cpt != null && personNamesLikelySame(ord, cpt) -> {
                        statut = StatutCheck.NON_CONFORME
                        detail = "Ordonnateur et comptable identiques (« $ord » ≈ « $cpt ») : separation des pouvoirs non respectee (decret 2-22-431 art. 21)."
                    }
                    ord == null || cpt == null -> {
                        statut = StatutCheck.NON_CONFORME
                        detail = "Un seul signataire identifie sur l'OP (ord=${ord ?: "absent"}, cpt=${cpt ?: "absent"}). Le decret 2-22-431 art. 21 exige deux signataires distincts (ordonnateur + comptable)."
                    }
                    else -> {
                        statut = StatutCheck.CONFORME
                        detail = "Deux signataires distincts identifies : ordonnateur « $ord » et comptable « $cpt »."
                    }
                }
                results += ResultatValidation(
                    dossier = dossier, regle = "R31",
                    libelle = "Separation des pouvoirs ordonnateur / comptable (decret 2-22-431 art. 21)",
                    statut = statut,
                    detail = detail,
                    valeurAttendue = "Deux signataires distincts",
                    valeurTrouvee = "ord=${ord ?: "?"} / cpt=${cpt ?: "?"}",
                    evidences = r31Evidences
                )
            }
        }

        // R24 : completude lignes facture au-dela d'un seuil montant. Au-dessus
        // du seuil, une facture sans lignes detaillees signale soit une
        // extraction degradee (Claude a manque le tableau), soit une facture
        // reelle sans detail — les deux cas meritent une relecture humaine
        // pour eviter de valider un montant significatif sans traçabilite.
        if (isEnabled("R24") && allFactures.isNotEmpty()) {
            measureRule("R24", results) {
                val seuil = completudeLignesSeuilTtc.toBigDecimal()
                for (f in allFactures) {
                    val ttc = f.montantTtc ?: continue
                    if (ttc < seuil) continue
                    val nbLignes = f.lignes.size
                    val numero = f.numeroFacture ?: "(sans numero)"
                    if (nbLignes == 0) {
                        results += ResultatValidation(
                            dossier = dossier, regle = "R24",
                            libelle = "Completude lignes facture",
                            statut = StatutCheck.AVERTISSEMENT,
                            detail = "Facture ${numero} (${ttc} MAD TTC) sans lignes detaillees " +
                                "alors que le montant depasse le seuil de ${seuil} MAD. " +
                                "Relire le document source : l'extraction a peut-etre manque le tableau, " +
                                "ou la facture elle-meme n'a pas de detail — les deux cas justifient une revue.",
                            valeurAttendue = ">= 1 ligne",
                            valeurTrouvee = "0 ligne"
                        )
                    } else {
                        results += ResultatValidation(
                            dossier = dossier, regle = "R24",
                            libelle = "Completude lignes facture",
                            statut = StatutCheck.CONFORME,
                            detail = "Facture ${numero} (${ttc} MAD TTC) avec ${nbLignes} ligne(s) detaillee(s)"
                        )
                    }
                }
            }
        }

        if (isEnabled("R12")) {
            measureRule("R12", results) {
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

                val ckResults = executeChecklistPoints(ctx, ckDocMapping)
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
        }

        if (isEnabled("R13")) {
            val tcPoints = tableau?.points?.toList() ?: run {
                @Suppress("UNCHECKED_CAST")
                val jsonPts = tcDoc?.donneesExtraites?.get("points") as? List<Map<String, Any?>> ?: emptyList()
                jsonPts.map { p -> PointControleFinancier(
                    tableauControle = tableau ?: TableauControle(dossier = dossier, document = tcDoc ?: dossier.documents.first()),
                    numero = (p["numero"] as? Number)?.toInt() ?: 0,
                    description = p["description"] as? String,
                    observation = p["observation"] as? String,
                    commentaire = p["commentaire"] as? String
                )}
            }
            if (tcPoints.isNotEmpty()) {
                val nonConformes = tcPoints.filter {
                    it.observation?.lowercase()?.contains("non conforme") == true
                }
                results += ResultatValidation(
                    dossier = dossier, regle = "R13",
                    libelle = "Tableau de controle financier complet",
                    statut = if (nonConformes.isEmpty()) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                    detail = if (nonConformes.isEmpty()) "Tous les points sont Conforme ou NA (${tcPoints.size} points)"
                        else "${nonConformes.size} point(s) Non conforme sur ${tcPoints.size}"
                )
            }
        }

        run {
            val dateBcContrat = bc?.dateBc ?: contrat?.dateSignature
                ?: docStr(bcDoc, "dateBc")?.let { parseLocalDate(it) }
                ?: docStr(contrat?.document, "dateSignature")?.let { parseLocalDate(it) }
            val dateFacture = facture?.dateFacture
                ?: docStr(fDoc, "dateFacture")?.let { parseLocalDate(it) }
            val dateOp = op?.dateEmission
                ?: docStr(opDoc, "dateEmission")?.let { parseLocalDate(it) }
            if (isEnabled("R17a") && dateBcContrat != null && dateFacture != null) {
                val ok = !dateFacture.isBefore(dateBcContrat)
                val refDoc = bcDoc ?: contrat?.document
                results += ResultatValidation(
                    dossier = dossier, regle = "R17a",
                    libelle = "Chronologie : date BC/Contrat <= date Facture",
                    // Une facture anterieure au BC/Contrat = engagement post-facto :
                    // scenario typique de regularisation a posteriori, bloquant.
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                    detail = if (ok) null else
                        "Facture du ${dateFacture} anterieure au BC/Contrat du ${dateBcContrat} : engagement non couvert au moment de la facturation.",
                    valeurAttendue = ">= ${dateBcContrat}", valeurTrouvee = dateFacture.toString(),
                    evidences = listOf(
                        evidence("attendu", if (bc != null) "dateBc" else "dateSignature", "Date BC / Contrat", refDoc, dateBcContrat),
                        evidence("trouve", "dateFacture", "Date de la facture", fDoc, dateFacture)
                    )
                )
            }
            if (isEnabled("R17b") && dateFacture != null && dateOp != null) {
                val ok = !dateOp.isBefore(dateFacture)
                results += ResultatValidation(
                    dossier = dossier, regle = "R17b",
                    libelle = "Chronologie : date Facture <= date OP",
                    // Un OP anterieur a la facture = paiement antidate, scenario fraude.
                    // Bloquant pour fiabilite 100%.
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                    detail = if (ok) null else
                        "OP emis le ${dateOp} avant la facture du ${dateFacture} : paiement antidate, incoherence chronologique.",
                    valeurAttendue = ">= ${dateFacture}", valeurTrouvee = dateOp.toString(),
                    evidences = listOf(
                        evidence("attendu", "dateFacture", "Date de la facture", fDoc, dateFacture),
                        evidence("trouve", "dateEmission", "Date d'emission de l'OP", opDoc, dateOp)
                    )
                )
            }
        }

        val arfDateEdition = arf?.dateEdition
            ?: docStr(arfDoc, "dateEdition")?.let { parseLocalDate(it) }
        if (isEnabled("R18") && arfDateEdition != null) {
            // La duree de validite varie selon le contexte (Circulaire DGI 717
            // / Instruction DGI 2014) :
            //   - Marche public : 3 mois (rappele par le decret 2-22-431)
            //   - B2B / BC / contrat hors marche : 6 mois (regle generale DGI)
            // Borne inclusive : l'attestation editee le 26/10/2025 reste valide
            // jusqu'a la fin de journee du 26/04/2026 (M+6) ; utiliser !isBefore
            // pour inclure le jour exact d'expiration. La date de reference est
            // `op.dateEmission` si dispo (= date du paiement, c'est elle qui
            // doit etre couverte par une attestation valide), sinon today().
            val isMarche = dossier.engagement?.typeEngagement() == com.madaef.recondoc.entity.engagement.TypeEngagement.MARCHE
            val mois = if (isMarche) 3L else 6L
            val refDate = op?.dateEmission ?: LocalDate.now()
            val limite = arfDateEdition.plusMonths(mois)
            val valide = !limite.isBefore(refDate)
            val source = if (isMarche) "Circulaire DGI 717 / decret 2-22-431 (3 mois marche public)"
                         else "Regle generale DGI (6 mois B2B)"
            results += ResultatValidation(
                dossier = dossier, regle = "R18",
                libelle = "Validite attestation fiscale (${mois} mois ${if (isMarche) "marche public" else "B2B"})",
                statut = if (valide) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                detail = "Editee le ${arfDateEdition}, valide jusqu'au ${limite} inclus, evaluee au ${refDate}. ${source}.",
                evidences = listOf(
                    evidence("source", "dateEdition", "Date d'edition de l'attestation", arfDoc, arfDateEdition),
                    evidence("calcule", "dateValidite", "Valide jusqu'au (+${mois} mois, inclus)", null, limite),
                    evidence("calcule", "dateReference", "Date de reference (OP ou aujourd'hui)", opDoc, refDate)
                )
            )
        }

        if (isEnabled("R19") && arf != null) {
            results += checkAttestationQr(arf, arfDoc, dossier)
        }

        if (isEnabled("R23") && arf != null) {
            results += checkAttestationRegularite(arf, arfDoc, dossier)
        }

        if (isEnabled("R16") && facture != null && fHt != null && fTva != null && fTtc != null) {
            val calcTtc = fHt.add(fTva)
            results += checkMontant("R16", "Verification arithmetique : HT + TVA = TTC",
                fTtc, calcTtc, tol, dossier,
                listOf(
                    evidence("source", "montantHT", "HT de la facture", fDoc, fHt),
                    evidence("source", "montantTVA", "TVA de la facture", fDoc, fTva),
                    evidence("calcule", "montantAttendu", "HT + TVA", null, calcTtc),
                    evidence("trouve", "montantTTC", "TTC de la facture", fDoc, fTtc)
                ))
        }

        // R16b: arithmetic per invoice line (quantite * prixUnitaireHT == montantTotalHT)
        if (isEnabled("R16b") && facture != null && facture.lignes.isNotEmpty()) measureRule("R16b", results) {
            val badLines = mutableListOf<String>()
            val r16bEvidences = mutableListOf<ValidationEvidence>()
            for ((idx, ligne) in facture.lignes.withIndex()) {
                val q = ligne.quantite
                val pu = ligne.prixUnitaireHt
                val mt = ligne.montantTotalHt
                if (q == null || pu == null || mt == null) continue
                val expected = q.multiply(pu).setScale(2, RoundingMode.HALF_UP)
                val diff = expected.subtract(mt).abs()
                val base = expected.abs().max(mt.abs()).max(BigDecimal.ONE)
                // Hybride absolu (rounding) + relatif strict (0.5%) pour ne pas
                // confondre tolerance-cents et tolerance-%.
                val limite = tol.max(base.multiply(tolPct))
                if (diff > limite) {
                    val label = ligne.designation.take(60)
                    badLines += "Ligne ${idx + 1} \"$label\" : ${q} x ${pu} = ${expected}, trouve ${mt}"
                    r16bEvidences += evidence("trouve", "ligne${idx + 1}", "Ligne ${idx + 1}: $label",
                        fDoc, "qte=${q}, pu=${pu}, total=${mt} (attendu ${expected})")
                }
            }
            if (badLines.isNotEmpty()) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R16b",
                    libelle = "Arithmetique des lignes (quantite x prix unitaire)",
                    statut = StatutCheck.NON_CONFORME,
                    detail = "${badLines.size} ligne(s) incoherente(s). " + badLines.joinToString(" | "),
                    evidences = r16bEvidences.ifEmpty { null }
                )
            } else {
                results += ResultatValidation(
                    dossier = dossier, regle = "R16b",
                    libelle = "Arithmetique des lignes (quantite x prix unitaire)",
                    statut = StatutCheck.CONFORME,
                    detail = "${facture.lignes.size} ligne(s) arithmetiquement coherente(s)"
                )
            }
        }

        // R16c: sum of line amounts equals facture HT
        if (isEnabled("R16c") && facture != null && facture.lignes.isNotEmpty() && fHt != null) {
            val sum = facture.lignes.mapNotNull { it.montantTotalHt }
                .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
                .setScale(2, RoundingMode.HALF_UP)
            results += checkMontant("R16c", "Somme des lignes = montant HT facture",
                sum, fHt, tol, dossier,
                listOf(
                    evidence("calcule", "sommeLignes",
                        "Somme des ${facture.lignes.size} ligne(s) de la facture", fDoc, sum),
                    evidence("attendu", "montantHT", "Montant HT de la facture", fDoc, fHt)
                ))
        }

        // R01f: total of invoice items matches total of BC items
        if (isEnabled("R01f") && dossier.type == DossierType.BC && facture != null && bcDoc != null
            && facture.lignes.isNotEmpty()) {
            val factureSum = facture.lignes.mapNotNull { it.montantTotalHt }
                .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
                .setScale(2, RoundingMode.HALF_UP)
            val bcLignes = parseBcLignes(bcDoc)
            val bcSum = bcLignes.mapNotNull { it.montantHt }
                .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
                .setScale(2, RoundingMode.HALF_UP)
            if (bcLignes.isNotEmpty()) {
                results += checkMontant("R01f", "Somme des lignes facture = somme des lignes BC",
                    factureSum, bcSum, tol, dossier,
                    listOf(
                        evidence("calcule", "sommeFactureLignes",
                            "Somme des ${facture.lignes.size} ligne(s) facture", fDoc, factureSum),
                        evidence("calcule", "sommeBcLignes",
                            "Somme des ${bcLignes.size} ligne(s) du BC", bcDoc, bcSum)
                    ))
            }
        }

        // R01g: matching ligne par ligne facture ↔ BC (ou grille tarifaire du contrat)
        if (isEnabled("R01g") && facture != null && facture.lignes.isNotEmpty()) {
            val refLines: List<BcLigne>
            val refDoc: Document?
            val refLabel: String
            when {
                dossier.type == DossierType.BC && bcDoc != null -> {
                    refLines = parseBcLignes(bcDoc)
                    refDoc = bcDoc
                    refLabel = "BC"
                }
                dossier.type == DossierType.CONTRACTUEL && contrat != null && contrat.grillesTarifaires.isNotEmpty() -> {
                    refLines = contrat.grillesTarifaires.map { g ->
                        BcLigne(
                            codeArticle = null,
                            designation = g.designation,
                            quantite = null,
                            prixUnitaireHt = g.prixUnitaireHt,
                            montantHt = null
                        )
                    }
                    refDoc = contrat.document
                    refLabel = "grille contrat"
                }
                else -> {
                    refLines = emptyList()
                    refDoc = null
                    refLabel = ""
                }
            }

            if (refLines.isNotEmpty()) {
                val issues = mutableListOf<String>()
                val r01gEvidences = mutableListOf<ValidationEvidence>()
                val used = mutableSetOf<Int>()
                var conformCount = 0

                for ((i, fl) in facture.lignes.withIndex()) {
                    val flLabel = fl.designation.take(60)
                    val match = findBestRefMatch(fl, refLines, used)
                    if (match == null) {
                        issues += "Ligne facture ${i + 1} « $flLabel » sans correspondance dans $refLabel"
                        r01gEvidences += evidence("trouve", "ligne${i + 1}",
                            "Ligne facture ${i + 1} sans correspondance", fDoc, fl.designation)
                        continue
                    }
                    used += match.first
                    val rl = match.second
                    val lineIssues = mutableListOf<String>()

                    val flQte = fl.quantite
                    val flPu = fl.prixUnitaireHt
                    val flTotal = fl.montantTotalHt
                    if (flQte != null && rl.quantite != null) {
                        val diff = flQte.subtract(rl.quantite).abs()
                        if (diff > tol) lineIssues += "qte facture=${flQte}, $refLabel=${rl.quantite}"
                    }
                    if (flPu != null && rl.prixUnitaireHt != null) {
                        val diff = flPu.subtract(rl.prixUnitaireHt).abs()
                        val base = rl.prixUnitaireHt.abs().max(BigDecimal.ONE)
                        // Hybride absolu + relatif strict : 0.5% ou 5 centimes,
                        // selon le plus permissif. Eviter `tol` seul comme % (laxiste).
                        val limite = tol.max(base.multiply(tolPct))
                        if (diff > limite) {
                            lineIssues += "PU facture=${flPu}, $refLabel=${rl.prixUnitaireHt}"
                        }
                    }
                    if (flTotal != null && rl.montantHt != null) {
                        val diff = flTotal.subtract(rl.montantHt).abs()
                        if (diff > tol) lineIssues += "total facture=${flTotal}, $refLabel=${rl.montantHt}"
                    }

                    if (lineIssues.isEmpty()) {
                        conformCount++
                    } else {
                        issues += "Ligne ${i + 1} « $flLabel » : ${lineIssues.joinToString("; ")}"
                        r01gEvidences += evidence("trouve", "ligne${i + 1}",
                            "Facture L${i + 1}: ${flLabel}", fDoc,
                            "qte=${fl.quantite}, pu=${fl.prixUnitaireHt}, total=${fl.montantTotalHt}")
                        r01gEvidences += evidence("attendu", "ligne${i + 1}",
                            "$refLabel: ${rl.designation?.take(60)}", refDoc,
                            "qte=${rl.quantite}, pu=${rl.prixUnitaireHt}, total=${rl.montantHt}")
                    }
                }

                if (dossier.type == DossierType.BC) {
                    val unmatchedRef = refLines.withIndex().filter { it.index !in used }
                    for ((idx, rl) in unmatchedRef) {
                        issues += "Ligne $refLabel ${idx + 1} « ${rl.designation?.take(50)} » absente de la facture"
                    }
                }

                results += ResultatValidation(
                    dossier = dossier, regle = "R01g",
                    libelle = "Correspondance ligne par ligne facture ↔ $refLabel",
                    statut = if (issues.isEmpty()) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                    detail = if (issues.isEmpty())
                        "${conformCount}/${facture.lignes.size} ligne(s) concordent"
                    else
                        "${conformCount}/${facture.lignes.size} concordent. " + issues.joinToString(" | "),
                    evidences = r01gEvidences.ifEmpty { null }
                )
            }
        }

        if (isEnabled("R15") && dossier.type == DossierType.CONTRACTUEL && contrat != null && facture != null) {
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
                        fHt, expectedHt, tol, dossier)
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
            "R15" to listOf(TypeDocument.FACTURE, TypeDocument.CONTRAT_AVENANT, TypeDocument.PV_RECEPTION),
            "R16" to listOf(TypeDocument.FACTURE),
            "R16b" to listOf(TypeDocument.FACTURE),
            "R16c" to listOf(TypeDocument.FACTURE),
            "R01f" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE),
            "R01g" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE, TypeDocument.CONTRAT_AVENANT),
            "R14" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE, TypeDocument.ORDRE_PAIEMENT, TypeDocument.ATTESTATION_FISCALE),
            "R14b" to listOf(TypeDocument.FACTURE, TypeDocument.ATTESTATION_FISCALE),
            "R17" to listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE, TypeDocument.ORDRE_PAIEMENT),
            "R18" to listOf(TypeDocument.ATTESTATION_FISCALE),
            "R19" to listOf(TypeDocument.ATTESTATION_FISCALE),
            "R20" to emptyList(),
        )
        for (r in results) {
            if (r.documentIds == null) {
                val types = ruleDocTypes[r.regle]
                if (types != null && types.isNotEmpty()) {
                    r.documentIds = dossier.documents
                        .filter { it.typeDocument in types }
                        .mapNotNull { it.id?.toString() }
                        .distinct()
                        .joinToString(",")
                        .ifBlank { null }
                }
            }
        }

        runCustomRules(dossier, isEnabled).let { results += it }

        return results
    }

    /**
     * Execute user-defined rules that are enabled both globally and for this
     * dossier type. Custom rules are stored under code "CUSTOM-XX" which flows
     * through the same rule_config / dossier_rule_override tables as the
     * deterministic rules, so the UI toggles Just Work.
     */
    private fun runCustomRules(dossier: DossierPaiement, isEnabled: (String) -> Boolean): List<ResultatValidation> {
        val customRules = try {
            customRuleService.listEnabled()
        } catch (e: Exception) {
            log.warn("Failed to load custom rules: {}", e.message)
            return emptyList()
        }
        if (customRules.isEmpty()) return emptyList()

        val selected = customRules
            .filter { isEnabled(it.code) }
            .filter {
                when (dossier.type) {
                    DossierType.BC -> it.appliesToBC
                    DossierType.CONTRACTUEL -> it.appliesToContractuel
                }
            }
        if (selected.isEmpty()) return emptyList()

        return try {
            customRuleService.evaluateBatch(selected, dossier)
        } catch (e: Exception) {
            log.warn("Batch custom-rule evaluation crashed ({}): falling back to per-rule", e.message)
            selected.map { rule ->
                try {
                    customRuleService.evaluate(rule, dossier)
                } catch (ex: Exception) {
                    log.warn("Custom rule {} crashed: {}", rule.code, ex.message)
                    ResultatValidation(
                        dossier = dossier, regle = rule.code, libelle = rule.libelle,
                        statut = StatutCheck.AVERTISSEMENT,
                        detail = "Erreur interne evaluation: ${ex.message?.take(200)}",
                        source = "CUSTOM"
                    )
                }
            }
        }
    }

    // checkMontant / checkMontantWithFraction / parseLocalDate / normalizeId /
    // computeMonths sont extraites dans ValidationHelpers.kt (meme package, donc
    // accessibles sans import). Cf commentaire dans le companion object.

    private fun executeChecklistPoints(
        ctx: ValidationContext,
        ckDocMapping: Map<Int, List<TypeDocument>>
    ): List<ResultatValidation> {
        val (dossier, facture, _, bc, op, contrat, pv, arf, checklist, _, tol) = ctx
        val ckResults = mutableListOf<ResultatValidation>()

        fun docIds(num: Int): String? {
            val types = ckDocMapping[num] ?: return null
            return dossier.documents.filter { it.typeDocument in types }
                .mapNotNull { it.id?.toString() }.joinToString(",").ifBlank { null }
        }

        fun ckPoint(pt: PointControle?): Pair<Boolean?, String?> =
            if (pt != null) (pt.estValide to pt.observation) else (null to null)

        val fDoc = facture?.document
        val bcDoc = bc?.document
        val opDoc = op?.document

        val fTtc = facture?.montantTtc ?: docAmount(fDoc, "montantTTC")
        val fHt = facture?.montantHt ?: docAmount(fDoc, "montantHT")
        val fTva = facture?.montantTva ?: docAmount(fDoc, "montantTVA")
        val bcTtc = bc?.montantTtc ?: docAmount(bcDoc, "montantTTC")

        val checklistDoc = dossier.documents.find { it.typeDocument == TypeDocument.CHECKLIST_AUTOCONTROLE }
        val entityPoints = checklist?.points?.sortedBy { it.numero } ?: emptyList()

        val points: List<PointControle> = if (entityPoints.isNotEmpty()) {
            entityPoints
        } else {
            @Suppress("UNCHECKED_CAST")
            val jsonPoints = checklistDoc?.donneesExtraites?.get("points") as? List<Map<String, Any?>> ?: emptyList()
            jsonPoints.mapNotNull { p ->
                val num = (p["numero"] as? Number)?.toInt() ?: return@mapNotNull null
                PointControle(
                    checklist = checklist ?: ChecklistAutocontrole(dossier = dossier, document = checklistDoc ?: fDoc ?: dossier.documents.first()),
                    numero = num,
                    description = p["description"] as? String,
                    estValide = parseBooleanish(p["estValide"]),
                    observation = p["observation"] as? String
                )
            }
        }

        run {
            val pt = points.find { it.numero == 1 }
            val (ckValide, obs) = ckPoint(pt)
            val hasBcOrContrat = bc != null || contrat != null
            val hasFacture = facture != null
            val montantMatch = when {
                facture == null -> false
                bc != null -> fTtc != null && bcTtc != null &&
                    fTtc.subtract(bcTtc).abs() <= tol
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
                valeurAttendue = if (bc != null) "BC: ${bcTtc?.toPlainString()}" else contrat?.referenceContrat,
                valeurTrouvee = fTtc?.toPlainString(),
                documentIds = docIds(1),
                evidences = listOfNotNull(
                    fTtc?.let { evidence("trouve", "montantTTC", "TTC de la facture", fDoc, it) },
                    bcTtc?.let { evidence("attendu", "montantTTC", "TTC du bon de commande", bcDoc, it) },
                    contrat?.referenceContrat?.let { evidence("source", "referenceContrat", "Reference du contrat", contrat.document, it) }
                ).ifEmpty { null }
            )
        }

        // CK02: Verification arithmetique des montants
        run {
            val pt = points.find { it.numero == 2 }
            val (ckValide, obs) = ckPoint(pt)
            val htOk = fHt != null && fTva != null && fTtc != null &&
                fHt.add(fTva).subtract(fTtc).abs() <= tol
            val sysStatut = when {
                facture == null -> StatutCheck.AVERTISSEMENT
                htOk -> StatutCheck.CONFORME
                fHt == null || fTva == null || fTtc == null -> StatutCheck.AVERTISSEMENT
                else -> StatutCheck.NON_CONFORME
            }
            val detail = if (htOk) "HT + TVA = TTC verifie" else if (facture == null) "Facture manquante" else
                "HT(${fHt}) + TVA(${fTva}) != TTC(${fTtc})"
            ckResults += ResultatValidation(
                dossier = dossier, regle = "R12.02",
                libelle = pt?.description ?: "Verification arithmetique des montants",
                statut = mergeStatut(sysStatut, ckValide),
                detail = listOfNotNull(detail, obs?.let { "Autocontrole: $it" }).joinToString(". "),
                source = "CHECKLIST",
                valeurAttendue = if (fHt != null && fTva != null) "${fHt} + ${fTva}" else null,
                valeurTrouvee = fTtc?.toPlainString(),
                documentIds = docIds(2),
                evidences = listOfNotNull(
                    fHt?.let { evidence("source", "montantHT", "HT de la facture", fDoc, it) },
                    fTva?.let { evidence("source", "montantTVA", "TVA de la facture", fDoc, it) },
                    fTtc?.let { evidence("trouve", "montantTTC", "TTC de la facture", fDoc, it) }
                ).ifEmpty { null }
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
            val fIce = facture?.ice ?: docStr(fDoc, "ice")
            val fIf = facture?.identifiantFiscal ?: docStr(fDoc, "identifiantFiscal")
            val fRc = facture?.rc ?: docStr(fDoc, "rc")
            val iceOk = fIce?.isNotBlank() == true
            val ifOk = fIf?.isNotBlank() == true
            val rcOk = fRc?.isNotBlank() == true
            val arfOk = arf?.estEnRegle == true || docStr(arf?.document, "estEnRegle") == "true"
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
                    "ICE: ${if (iceOk) fIce else "absent"}",
                    "IF: ${if (ifOk) fIf else "absent"}",
                    "RC: ${if (rcOk) fRc else "absent"}",
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
            val factureRib = normalizeRib(facture?.rib ?: docStr(fDoc, "rib"))
            val opRib = normalizeRib(op?.rib ?: docStr(opDoc, "rib"))
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

    // mergeStatut extrait dans ValidationHelpers.kt (top-level dans meme package).

    // R19 : QR DGI doit encoder le meme "Code de verification" que celui imprime
    // sous le QR ET etre servi par attestation.tax.gov.ma en HTTPS. Toute autre
    // forme (javascript:, data:, host externe, URL malformee) = NON_CONFORME
    // meme si le code hex coincide par hasard.
    private fun checkAttestationQr(arf: AttestationFiscale, arfDoc: Document?, dossier: DossierPaiement): ResultatValidation {
        val printedCode = arf.codeVerification?.trim()?.takeIf { it.isNotBlank() }
        val qrCode = arf.qrCodeExtrait?.trim()?.takeIf { it.isNotBlank() }
        val qrPayload = arf.qrPayload?.trim()?.takeIf { it.isNotBlank() }
        val qrHost = arf.qrHost?.trim()?.takeIf { it.isNotBlank() }
        val officialHost = QrCodeService.isOfficialDgiHost(qrHost)
        val canonicalHost = QrCodeService.isCanonicalAttestationHost(qrHost)
        val safety = QrCodeService.assessPayloadSafety(qrPayload)

        val evidences = mutableListOf<ValidationEvidence>().apply {
            add(evidence("source", "qrPayload", "Contenu du QR code", arfDoc, qrPayload ?: "(absent)"))
            add(evidence("trouve", "qrCodeExtrait", "Code extrait du QR", arfDoc, qrCode))
            add(evidence("attendu", "codeVerification", "Code imprime sous le QR", arfDoc, printedCode))
            if (qrHost != null) {
                add(evidence("source", "qrHost", "Domaine cible du QR", arfDoc, qrHost))
            }
            add(evidence("calcule", "qrHostOfficiel", "Domaine attendu (DGI)", null, "attestation.tax.gov.ma"))
            add(evidence("calcule", "qrSafety", "Verification securite du QR", null, safety.verdict.name))
        }

        val (statut, detail) = when {
            qrPayload == null -> {
                val reason = arf.qrScanError ?: "Aucun QR code lisible sur le document"
                StatutCheck.NON_CONFORME to "QR code introuvable ou illisible : $reason"
            }
            safety.verdict == QrCodeService.PayloadVerdict.DANGEROUS ->
                StatutCheck.NON_CONFORME to "QR potentiellement dangereux : ${safety.reason ?: "contenu suspect"}"
            qrCode == null -> StatutCheck.AVERTISSEMENT to "QR decode mais aucun code de verification extractible du contenu"
            printedCode == null -> StatutCheck.AVERTISSEMENT to "QR decode (${qrCode}) mais code imprime non extrait par l'OCR — verification visuelle requise"
            normalizeCode(printedCode) != normalizeCode(qrCode) -> StatutCheck.NON_CONFORME to
                "Incoherence : code imprime = '$printedCode', code QR = '$qrCode'"
            !officialHost && qrHost != null -> StatutCheck.NON_CONFORME to
                "Domaine inattendu : $qrHost. L'attestation doit emaner de attestation.tax.gov.ma (DGI)"
            safety.verdict == QrCodeService.PayloadVerdict.UNSAFE ->
                StatutCheck.AVERTISSEMENT to "Codes coherents ($printedCode) mais ${safety.reason}"
            officialHost && !canonicalHost ->
                StatutCheck.AVERTISSEMENT to "Codes coherents ($printedCode) sur $qrHost — domaine officiel mais different de attestation.tax.gov.ma"
            else -> StatutCheck.CONFORME to "Codes coherents ($printedCode)" +
                (qrHost?.let { " sur $it" } ?: "")
        }

        return ResultatValidation(
            dossier = dossier, regle = "R19",
            libelle = "QR code attestation fiscale (origine DGI + coherence code)",
            statut = statut,
            detail = detail,
            valeurAttendue = printedCode, valeurTrouvee = qrCode,
            evidences = evidences
        )
    }

    // R23 : estEnRegle=true requis. null = AVERTISSEMENT (relecture humaine).
    private fun checkAttestationRegularite(arf: AttestationFiscale, arfDoc: Document?, dossier: DossierPaiement): ResultatValidation {
        val estEnRegle = arf.estEnRegle
        val (statut, detail) = when (estEnRegle) {
            true -> StatutCheck.CONFORME to "Attestation confirme la regularite fiscale"
            false -> StatutCheck.NON_CONFORME to "L'attestation indique que la societe n'est PAS en situation reguliere"
            null -> StatutCheck.AVERTISSEMENT to "Regularite non extraite — relecture manuelle requise"
        }
        return ResultatValidation(
            dossier = dossier, regle = "R23",
            libelle = "Regularite fiscale de la societe",
            statut = statut,
            detail = detail,
            valeurAttendue = "estEnRegle=true",
            valeurTrouvee = estEnRegle?.toString() ?: "null",
            evidences = listOf(
                evidence("source", "estEnRegle", "Regularite declaree par l'attestation", arfDoc, estEnRegle?.toString())
            )
        )
    }

    /**
     * Liste des pieces obligatoires pour R20.
     * `customRequired` (CSV de TypeDocument) prime ; null = defauts par type.
     * Les types inconnus dans la config heritee sont ignores silencieusement.
     */
    fun resolveRequiredDocuments(type: DossierType, customRequired: String?): List<Pair<TypeDocument, String>> {
        val selection = parseCustomTypes(customRequired) ?: RuleConstants.DEFAULT_REQUIRED_BY_TYPE.getValue(type)
        return selection.map { it to RuleConstants.TYPE_LABELS.getValue(it) }
    }

    // Les helpers suivants vivent maintenant en top-level dans ValidationHelpers.kt
    // et BcLigneMatcher.kt (meme package). Ils sont accessibles sans import depuis
    // cette classe : normalizeCode, parseCustomTypes, BcLigne, parseBcLignes,
    // normalizeLabel, labelSimilarity, findBestRefMatch, toBd, matchReference.
}
