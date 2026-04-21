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

        private val NUMERIC_CLEAN_RE = "[^\\d.,\\-]".toRegex()

        fun docAmount(doc: Document?, vararg keys: String): BigDecimal? {
            val data = doc?.donneesExtraites ?: return null
            for (k in keys) {
                val v = data[k] ?: data.entries.find { it.key.equals(k, ignoreCase = true) }?.value ?: continue
                return when (v) {
                    is Number -> BigDecimal(v.toString())
                    is String -> v.replace(NUMERIC_CLEAN_RE, "").let { s ->
                        if (s.isEmpty()) return@let null
                        val lc = s.lastIndexOf(','); val ld = s.lastIndexOf('.')
                        if (lc > ld) s.replace(".", "").replace(",", ".").toBigDecimalOrNull()
                        else s.replace(",", "").toBigDecimalOrNull()
                    }
                    else -> null
                }
            }
            return null
        }

        fun docStr(doc: Document?, vararg keys: String): String? {
            val data = doc?.donneesExtraites ?: return null
            for (k in keys) {
                val v = data[k] ?: data.entries.find { it.key.equals(k, ignoreCase = true) }?.value
                if (v != null && v.toString().isNotBlank()) return v.toString()
            }
            return null
        }

        private val TRUTHY = setOf("true", "oui", "conforme", "o", "yes")
        private val FALSY = setOf("false", "non", "non conforme", "n", "no")

        fun parseBooleanish(v: Any?): Boolean? = when (v) {
            is Boolean -> v
            is String -> v.lowercase().trim().let { s -> when { s in TRUTHY -> true; s in FALSY -> false; else -> null } }
            is Number -> v.toInt() != 0
            else -> null
        }

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
            "R16" to setOf("R04", "R05", "R15", "R16b", "R16c"),
            "R16b" to setOf("R16", "R16c"),
            "R16c" to setOf("R16", "R16b", "R01f"),
            "R01f" to setOf("R01", "R02", "R16c", "R01g"),
            "R01g" to setOf("R01", "R02", "R01f", "R15"),
            "R14b" to setOf("R09", "R10", "R14"),
            "R17a" to setOf("R17b"),
            "R17b" to setOf("R17a"),
            "R18" to emptySet(),
            "R19" to emptySet(),
            "R20" to emptySet(),
            "R12" to emptySet(),
            "R13" to emptySet(),
        )
    }

    private data class CorrectionSnapshot(
        val statut: StatutCheck, val statutOriginal: String?,
        val commentaire: String?, val corrigePar: String?,
        val dateCorrection: LocalDateTime?
    )

    private data class ValidationContext(
        val dossier: DossierPaiement,
        val facture: Facture?,
        val allFactures: List<Facture>,
        val bc: BonCommande?,
        val op: OrdrePaiement?,
        val contrat: ContratAvenant?,
        val pv: PvReception?,
        val arf: AttestationFiscale?,
        val checklist: ChecklistAutocontrole?,
        val tableau: TableauControle?,
        val tol: BigDecimal
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

    private fun evidence(role: String, champ: String, libelle: String?, doc: Document?, valeur: Any?): ValidationEvidence =
        ValidationEvidence(
            role = role, champ = champ, libelle = libelle,
            documentId = doc?.id?.toString(),
            documentType = doc?.typeDocument?.name,
            valeur = valeur?.toString()?.takeIf { it.isNotBlank() }
        )

    @Transactional
    fun validate(dossier: DossierPaiement): List<ResultatValidation> {
        log.info("Running validation for dossier {}", dossier.reference)
        dossier.resultatsValidation.clear()
        resultatRepository.deleteByDossierId(dossier.id!!)
        resultatRepository.flush()

        val isEnabled = loadEnabledRules(dossier.id!!)
        val results = runAllRules(dossier, isEnabled)
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
        val corrected = existingResults
            .filter { it.regle in rulesToRun && it.statutOriginal != null }
            .associate { it.regle to CorrectionSnapshot(it.statut, it.statutOriginal, it.commentaire, it.corrigePar, it.dateCorrection) }

        val toDelete = existingResults.filter { it.regle in rulesToRun }
        resultatRepository.deleteAll(toDelete)
        resultatRepository.flush()

        val isEnabled: (String) -> Boolean = { it in rulesToRun }
        val allResults = runAllRules(dossier, isEnabled)
        allResults.forEach { r ->
            r.dateExecution = LocalDateTime.now()
            val prev = corrected[r.regle]
            if (prev != null) {
                r.statutOriginal = r.statut.name
                r.statut = prev.statut
                r.commentaire = prev.commentaire
                r.corrigePar = prev.corrigePar
                r.dateCorrection = prev.dateCorrection
            }
        }
        resultatRepository.saveAll(allResults)

        return allResults
    }

    private fun runAllRules(dossier: DossierPaiement, isEnabled: (String) -> Boolean): List<ResultatValidation> {
        val results = mutableListOf<ResultatValidation>()
        val tol = BigDecimal(toleranceMontant)
        val ctx = ValidationContext(
            dossier = dossier,
            facture = dossier.factures.firstOrNull(),
            allFactures = dossier.factures.toList(),
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
                },
                evidences = listOf(
                    evidence("source", "ice", "ICE sur la facture", fDoc, iceFacture),
                    evidence("source", "ice", "ICE sur l'attestation fiscale", arfDoc, iceArf)
                )
            )
        }

        if (isEnabled("R10")) {
            val ifFacture = (facture?.identifiantFiscal ?: docStr(fDoc, "identifiantFiscal"))?.trim()?.takeIf { it.isNotBlank() }
            val ifArf = (arf?.identifiantFiscal ?: docStr(arfDoc, "identifiantFiscal"))?.trim()?.takeIf { it.isNotBlank() }
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

            val hasMatch = combinedFactureRibs.any { fr -> allOpRibs.any { or -> fr == or } }
            val docIds = allFactures.mapNotNull { it.document.id?.toString() } + listOfNotNull(op.document.id?.toString())

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
                    hasMatch -> StatutCheck.CONFORME
                    else -> StatutCheck.NON_CONFORME
                },
                valeurAttendue = "Factures: ${combinedFactureRibs.joinToString(", ").ifBlank { "Aucun RIB" }}",
                valeurTrouvee = "OP: ${allOpRibs.joinToString(", ").ifBlank { "Aucun RIB" }}",
                detail = when {
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

            val fournisseurs = (listOfNotNull(
                dossier.fournisseur,
                bcFourn, opBenef, tcFourn, ckPrest, arfRaison
            ) + allFactures.mapNotNull { it.fournisseur ?: docStr(it.document, "fournisseur") }).map { it.trim().lowercase() }.distinct()
            if (fournisseurs.size > 1) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R14",
                    libelle = "Coherence fournisseur entre documents",
                    statut = StatutCheck.AVERTISSEMENT,
                    detail = "Fournisseurs differents: ${fournisseurs.joinToString(", ")}",
                    evidences = r14Evidences.ifEmpty { null }
                )
            } else if (fournisseurs.isNotEmpty()) {
                results += ResultatValidation(
                    dossier = dossier, regle = "R14",
                    libelle = "Coherence fournisseur entre documents",
                    statut = StatutCheck.CONFORME,
                    detail = "Fournisseur: ${fournisseurs.first()}",
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
            if (fIce != null && arfIce != null && normalizeId(fIce) != normalizeId(arfIce)) {
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

        if (isEnabled("R12")) {
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
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.AVERTISSEMENT,
                    valeurAttendue = dateBcContrat.toString(), valeurTrouvee = dateFacture.toString(),
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
                    statut = if (ok) StatutCheck.CONFORME else StatutCheck.AVERTISSEMENT,
                    valeurAttendue = dateFacture.toString(), valeurTrouvee = dateOp.toString(),
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
            val valide = arfDateEdition.plusMonths(6).isAfter(LocalDate.now())
            results += ResultatValidation(
                dossier = dossier, regle = "R18",
                libelle = "Validite attestation fiscale (6 mois)",
                statut = if (valide) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
                detail = "Editee le ${arfDateEdition}, valide jusqu'au ${arfDateEdition.plusMonths(6)}",
                evidences = listOf(
                    evidence("source", "dateEdition", "Date d'edition de l'attestation", arfDoc, arfDateEdition),
                    evidence("calcule", "dateValidite", "Valide jusqu'au (+6 mois)", null, arfDateEdition.plusMonths(6))
                )
            )
        }

        if (isEnabled("R19") && arf != null) {
            results += checkAttestationQr(arf, arfDoc, dossier)
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
        if (isEnabled("R16b") && facture != null && facture.lignes.isNotEmpty()) {
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
                if (diff.divide(base, 6, RoundingMode.HALF_UP) > tol) {
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
                        if (diff.divide(base, 6, RoundingMode.HALF_UP) > tol) {
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

    private fun checkMontant(
        regle: String, libelle: String,
        valeur1: BigDecimal?, valeur2: BigDecimal?,
        tolerance: BigDecimal, dossier: DossierPaiement,
        evidences: List<ValidationEvidence>? = null
    ): ResultatValidation {
        if (valeur1 == null || valeur2 == null) {
            return ResultatValidation(
                dossier = dossier, regle = regle, libelle = libelle,
                statut = StatutCheck.AVERTISSEMENT,
                detail = "Valeur manquante",
                valeurAttendue = valeur2?.toPlainString(), valeurTrouvee = valeur1?.toPlainString(),
                evidences = evidences
            )
        }
        val diff = valeur1.subtract(valeur2).abs()
        val ok = diff <= tolerance
        return ResultatValidation(
            dossier = dossier, regle = regle, libelle = libelle,
            statut = if (ok) StatutCheck.CONFORME else StatutCheck.NON_CONFORME,
            detail = "${valeur1.toPlainString()} vs ${valeur2.toPlainString()} (ecart: ${diff.toPlainString()})",
            valeurAttendue = valeur2.toPlainString(), valeurTrouvee = valeur1.toPlainString(),
            evidences = evidences
        )
    }

    private fun checkMontantWithFraction(
        regle: String, libelle: String,
        factureVal: BigDecimal?, bcVal: BigDecimal?,
        tolerance: BigDecimal, dossier: DossierPaiement,
        evidences: List<ValidationEvidence>? = null
    ): ResultatValidation {
        val result = checkMontant(regle, libelle, factureVal, bcVal, tolerance, dossier, evidences)
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
                    valeurAttendue = bcVal.toPlainString(), valeurTrouvee = factureVal.toPlainString(),
                    evidences = evidences
                )
            }
        }
        return result
    }

    private fun parseLocalDate(s: String): LocalDate? {
        return try {
            LocalDate.parse(s)
        } catch (_: Exception) {
            try {
                val parts = s.split("/", "-", ".")
                if (parts.size == 3) {
                    val d = parts[0].trim().toInt()
                    val m = parts[1].trim().toInt()
                    val y = parts[2].trim().toInt().let { if (it < 100) it + 2000 else it }
                    LocalDate.of(y, m, d)
                } else null
            } catch (_: Exception) { null }
        }
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

    private fun mergeStatut(systemStatut: StatutCheck, checklistValide: Boolean?): StatutCheck {
        if (checklistValide == null) return systemStatut
        val ckStatut = if (checklistValide) StatutCheck.CONFORME else StatutCheck.NON_CONFORME
        return if (systemStatut == StatutCheck.NON_CONFORME || ckStatut == StatutCheck.NON_CONFORME) StatutCheck.NON_CONFORME
        else if (systemStatut == StatutCheck.AVERTISSEMENT || ckStatut == StatutCheck.AVERTISSEMENT) StatutCheck.AVERTISSEMENT
        else StatutCheck.CONFORME
    }

    /**
     * R19: Verify the QR code on the DGI "attestation de regularite fiscale".
     * The attestation prints a "Code de verification" under the QR; the QR
     * itself encodes a tax.gov.ma URL that carries the same code. A mismatch
     * between them (or a missing/unreadable QR) suggests tampering or a
     * photocopy of an outdated attestation.
     */
    private fun checkAttestationQr(arf: AttestationFiscale, arfDoc: Document?, dossier: DossierPaiement): ResultatValidation {
        val printedCode = arf.codeVerification?.trim()?.takeIf { it.isNotBlank() }
        val qrCode = arf.qrCodeExtrait?.trim()?.takeIf { it.isNotBlank() }
        val qrPayload = arf.qrPayload?.trim()?.takeIf { it.isNotBlank() }
        val qrHost = arf.qrHost?.trim()?.takeIf { it.isNotBlank() }
        val officialHost = QrCodeService.isOfficialDgiHost(qrHost)

        val evidences = mutableListOf<ValidationEvidence>().apply {
            add(evidence("source", "qrPayload", "Contenu du QR code", arfDoc, qrPayload ?: "(absent)"))
            add(evidence("trouve", "qrCodeExtrait", "Code extrait du QR", arfDoc, qrCode))
            add(evidence("attendu", "codeVerification", "Code imprime sous le QR", arfDoc, printedCode))
            if (qrHost != null) {
                add(evidence("source", "qrHost", "Domaine cible du QR", arfDoc, qrHost))
            }
        }

        val (statut, detail) = when {
            qrPayload == null -> {
                val reason = arf.qrScanError ?: "Aucun QR code lisible sur le document"
                StatutCheck.NON_CONFORME to "QR code introuvable ou illisible : $reason"
            }
            qrCode == null -> StatutCheck.AVERTISSEMENT to "QR decode mais aucun code de verification extractible du contenu"
            printedCode == null -> StatutCheck.AVERTISSEMENT to "QR decode (${qrCode}) mais code imprime non extrait par l'OCR — verification visuelle requise"
            normalizeCode(printedCode) != normalizeCode(qrCode) -> StatutCheck.NON_CONFORME to
                "Incoherence : code imprime = '$printedCode', code QR = '$qrCode'"
            !officialHost && qrHost != null -> StatutCheck.AVERTISSEMENT to
                "Codes coherents ($printedCode) mais domaine inattendu : $qrHost (attendu tax.gov.ma)"
            else -> StatutCheck.CONFORME to "Codes coherents ($printedCode)" +
                (qrHost?.let { " sur $it" } ?: "")
        }

        return ResultatValidation(
            dossier = dossier, regle = "R19",
            libelle = "QR code attestation fiscale coherent avec le code imprime",
            statut = statut,
            detail = detail,
            valeurAttendue = printedCode, valeurTrouvee = qrCode,
            evidences = evidences
        )
    }

    private fun normalizeCode(code: String): String =
        code.trim().lowercase().replace(Regex("[\\s\\-_|/.]+"), "")

    private data class BcLigne(
        val codeArticle: String?,
        val designation: String?,
        val quantite: BigDecimal?,
        val prixUnitaireHt: BigDecimal?,
        val montantHt: BigDecimal?
    )

    private fun parseBcLignes(doc: Document?): List<BcLigne> {
        val raw = doc?.donneesExtraites?.get("lignes") as? List<*> ?: return emptyList()
        return raw.mapNotNull { row ->
            @Suppress("UNCHECKED_CAST")
            val m = row as? Map<String, Any?> ?: return@mapNotNull null
            BcLigne(
                codeArticle = (m["codeArticle"] as? String)?.trim()?.takeIf { it.isNotBlank() },
                designation = (m["designation"] as? String)?.trim(),
                quantite = toBd(m["quantite"]),
                prixUnitaireHt = toBd(m["prixUnitaireHT"] ?: m["prixUnitaireHt"]),
                montantHt = toBd(m["montantLigneHT"] ?: m["montantLigneHt"] ?: m["montantTotalHt"] ?: m["montantHT"])
            )
        }
    }

    private fun normalizeLabel(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val nfd = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        return nfd
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun labelSimilarity(a: String?, b: String?): Double {
        val na = normalizeLabel(a); val nb = normalizeLabel(b)
        if (na.isBlank() || nb.isBlank()) return 0.0
        if (na == nb) return 1.0
        val ta = na.split(" ").filter { it.length > 1 }.toSet()
        val tb = nb.split(" ").filter { it.length > 1 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        return inter / union
    }

    private fun findBestRefMatch(
        fl: LigneFacture, refs: List<BcLigne>, used: Set<Int>
    ): Pair<Int, BcLigne>? {
        val flCode = fl.codeArticle?.trim()?.takeIf { it.isNotBlank() }
        if (flCode != null) {
            val exact = refs.withIndex().firstOrNull { (i, r) ->
                i !in used && r.codeArticle != null && r.codeArticle.equals(flCode, ignoreCase = true)
            }
            if (exact != null) return exact.index to exact.value
        }
        var best: Pair<Int, BcLigne>? = null
        var bestScore = 0.60
        for ((i, r) in refs.withIndex()) {
            if (i in used) continue
            val score = labelSimilarity(fl.designation, r.designation)
            if (score > bestScore) {
                bestScore = score
                best = i to r
            }
        }
        return best
    }

    private fun toBd(v: Any?): BigDecimal? = when (v) {
        null -> null
        is Number -> BigDecimal(v.toString())
        is String -> v.replace(Regex("[^0-9.,\\-]"), "").let { s ->
            if (s.isEmpty()) return@let null
            val lc = s.lastIndexOf(','); val ld = s.lastIndexOf('.')
            if (lc > ld) s.replace(".", "").replace(",", ".").toBigDecimalOrNull()
            else s.replace(",", "").toBigDecimalOrNull()
        }
        else -> null
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
