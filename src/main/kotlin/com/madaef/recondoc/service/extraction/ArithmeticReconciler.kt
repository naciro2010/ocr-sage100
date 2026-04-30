package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.entity.dossier.TypeDocument
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reconciliation arithmetique post-extraction (Sprint 1 #5 fiabilite).
 *
 * Detecte et corrige les incoherences entre `montantHT`, `montantTVA`,
 * `montantTTC` et `tauxTVA` (et leurs equivalents engagement `montantHt` /
 * `montantTtc`). Trois axes :
 *
 *  1. **Auto-correction arrondi** : si HT+TVA s'ecarte de TTC d'au plus
 *     `app.tolerance-montant` (defaut 0.05 MAD), aligner TTC = HT+TVA.
 *     Reste sous le radar des regles R16 (qui tolere deja la meme
 *     marge), mais nettoie la donnee pour les rapprochements R01-R03
 *     entre facture et BC.
 *
 *  2. **Recompute du champ manquant** : si un seul des trois (HT/TVA/TTC)
 *     est absent et les deux autres presents, le calculer. Defends
 *     contre les schemas qui omettent une ligne du recapitulatif (cas
 *     frequent sur OCR de factures dactylographiees).
 *
 *  3. **Detection des incoherences irrecuperables** : si HT+TVA ≠ TTC
 *     au-dela de la tolerance ET que `tauxTVA` ne permet pas de
 *     determiner quel champ est faux, ajouter un warning explicite et
 *     signaler les champs candidats a re-extraction. Pas de correction
 *     destructive : la donnee reste intacte, l'incident remonte au
 *     score qualite et au scoring R16.
 *
 * Le verdict regulier des regles R01-R03, R16, R30 reste la source de
 * verite finale ; le reconciler ne fait QUE nettoyer les arrondis et
 * combler les trous, jamais inventer une valeur depuis le neant. Toute
 * action est tracee dans `_warnings`.
 *
 * Activable / desactivable via `app.arithmetic-reconciler.enabled`
 * (defaut true). Inerte sur les types de documents qui n'ont pas de
 * triplet HT/TVA/TTC (PV, checklists).
 */
@Service
class ArithmeticReconciler(
    @Value("\${app.tolerance-montant:0.05}") private val toleranceAbsRaw: String,
    @Value("\${app.tolerance-montant-pct:0.005}") private val tolerancePctRaw: String,
    @Value("\${app.arithmetic-reconciler.enabled:true}") private val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val tolAbs: BigDecimal by lazy { BigDecimal(toleranceAbsRaw) }
    private val tolPct: BigDecimal by lazy { BigDecimal(tolerancePctRaw) }

    private val tauxLegaux: Set<BigDecimal> = setOf(
        BigDecimal("0"), BigDecimal("7"), BigDecimal("10"),
        BigDecimal("14"), BigDecimal("20")
    )

    data class Correction(
        val field: String,
        val before: BigDecimal?,
        val after: BigDecimal,
        val reason: String
    )

    data class Reconciliation(
        val data: Map<String, Any?>,
        val corrections: List<Correction>,
        val unresolvedDetails: List<String>,
        /**
         * Champs sur lesquels une re-extraction ciblee est recommandee
         * (consomme par ExtractionQualityService / DossierService dans une
         * etape ulterieure). Vide si tout a ete reconcilie.
         */
        val candidatesForReextraction: List<String>
    )

    /**
     * Cles HT/TVA/TTC/taux varient entre le namespace "facture" (montantHT)
     * et "engagement" (montantHt). On externalise les noms pour partager
     * la logique entre les deux familles.
     */
    private data class FieldNames(
        val ht: String,
        val tva: String,
        val ttc: String,
        val taux: String
    )

    private fun fieldNamesFor(type: TypeDocument): FieldNames? = when (type) {
        TypeDocument.FACTURE,
        TypeDocument.BON_COMMANDE -> FieldNames("montantHT", "montantTVA", "montantTTC", "tauxTVA")
        TypeDocument.MARCHE,
        TypeDocument.BON_COMMANDE_CADRE,
        TypeDocument.CONTRAT_CADRE -> FieldNames("montantHt", "montantTva", "montantTtc", "tauxTva")
        else -> null
    }

    fun reconcile(type: TypeDocument, data: Map<String, Any?>): Reconciliation {
        if (!enabled) return Reconciliation(data, emptyList(), emptyList(), emptyList())
        val names = fieldNamesFor(type)
            ?: return Reconciliation(data, emptyList(), emptyList(), emptyList())
        return reconcileTriplet(data, names)
    }

    private fun reconcileTriplet(data: Map<String, Any?>, names: FieldNames): Reconciliation {
        val ht = numericField(data, names.ht)
        val tva = numericField(data, names.tva)
        val ttc = numericField(data, names.ttc)
        val taux = numericField(data, names.taux)

        // Au moins 2 valeurs sur 3 pour pouvoir reconcilier.
        val present = listOfNotNull(ht, tva, ttc).size
        if (present < 2) return Reconciliation(data, emptyList(), emptyList(), emptyList())

        val corrections = mutableListOf<Correction>()
        val unresolved = mutableListOf<String>()
        val needsReextract = mutableSetOf<String>()
        val newData = data.toMutableMap()

        when {
            // Cas A : les trois presents -> verifier HT+TVA = TTC.
            ht != null && tva != null && ttc != null -> {
                val expectedTtc = ht.add(tva)
                val diff = expectedTtc.subtract(ttc).abs()
                val limit = tolerance(ttc)

                when {
                    diff.signum() == 0 -> Unit // exact match
                    diff <= tolAbs -> {
                        // Arrondi tolere (<= 0.05 MAD typiquement) : auto-correction TTC.
                        newData[names.ttc] = expectedTtc
                        corrections += Correction(
                            names.ttc, ttc, expectedTtc,
                            "ajustement arrondi (ecart=${diff.setScale(2, RoundingMode.HALF_UP)} MAD)"
                        )
                    }
                    diff > limit -> {
                        // Ecart > tolerance hybride. Tentative de resolution via tauxTVA.
                        val resolved = tryResolveByTaux(
                            ht, tva, ttc, taux, names, newData, corrections
                        )
                        if (!resolved) {
                            unresolved += "${names.ht}+${names.tva}=${expectedTtc.setScale(2, RoundingMode.HALF_UP)} != ${names.ttc}=${ttc.setScale(2, RoundingMode.HALF_UP)} (ecart=${diff.setScale(2, RoundingMode.HALF_UP)} MAD)"
                            // Tous les 3 sont candidats car on ne sait pas lequel a foire.
                            needsReextract += listOf(names.ht, names.tva, names.ttc)
                        }
                    }
                    else -> Unit // diff entre tolAbs et limit : on tolere sans corriger
                }
            }

            // Cas B : TTC manquant -> calculer depuis HT+TVA.
            ht != null && tva != null && ttc == null -> {
                val computed = ht.add(tva)
                if (computed.signum() > 0) {
                    newData[names.ttc] = computed
                    corrections += Correction(
                        names.ttc, null, computed,
                        "calcule depuis ${names.ht}+${names.tva} (${names.ttc} absent du document)"
                    )
                }
            }

            // Cas C : HT manquant -> calculer depuis TTC-TVA si plausible.
            ht == null && tva != null && ttc != null -> {
                val computed = ttc.subtract(tva)
                if (computed.signum() >= 0) {
                    newData[names.ht] = computed
                    corrections += Correction(
                        names.ht, null, computed,
                        "calcule depuis ${names.ttc}-${names.tva} (${names.ht} absent du document)"
                    )
                } else {
                    unresolved += "${names.tva} (${tva.setScale(2)}) > ${names.ttc} (${ttc.setScale(2)}) -> impossible de calculer ${names.ht}"
                    needsReextract += listOf(names.tva, names.ttc)
                }
            }

            // Cas D : TVA manquante -> calculer depuis TTC-HT.
            ht != null && tva == null && ttc != null -> {
                val computed = ttc.subtract(ht)
                if (computed.signum() >= 0) {
                    newData[names.tva] = computed
                    corrections += Correction(
                        names.tva, null, computed,
                        "calcule depuis ${names.ttc}-${names.ht} (${names.tva} absent du document)"
                    )
                } else {
                    unresolved += "${names.ht} (${ht.setScale(2)}) > ${names.ttc} (${ttc.setScale(2)}) -> impossible de calculer ${names.tva}"
                    needsReextract += listOf(names.ht, names.ttc)
                }
            }
        }

        // Verification supplementaire : tauxTVA hors liste legale (R30 anticipee).
        if (taux != null && taux !in tauxLegaux) {
            // Tentative : trouver le taux legal le plus proche qui satisfait
            // HT*taux ≈ TVA. Si trouve, signaler dans les warnings (sans muter
            // tauxTVA pour ne pas cacher une vraie erreur a R30).
            val htForTaux = numericField(newData, names.ht)
            val tvaForTaux = numericField(newData, names.tva)
            if (htForTaux != null && htForTaux.signum() > 0 && tvaForTaux != null) {
                val effectiveTaux = tvaForTaux
                    .multiply(BigDecimal(100))
                    .divide(htForTaux, 2, RoundingMode.HALF_UP)
                val nearestLegal = tauxLegaux.minByOrNull { (effectiveTaux - it).abs() }
                if (nearestLegal != null && (effectiveTaux - nearestLegal).abs() <= BigDecimal("0.5")) {
                    unresolved += "${names.taux}=${taux} hors liste legale Maroc {0,7,10,14,20}, taux effectif HT*taux=TVA -> ${nearestLegal} (revue humaine pour fixer ${names.taux})"
                    needsReextract += names.taux
                }
            }
        }

        if (corrections.isNotEmpty() || unresolved.isNotEmpty()) {
            appendWarnings(newData, corrections, unresolved)
            log.info("ArithmeticReconciler: corrections={}, unresolved={}, candidates={}",
                corrections.size, unresolved.size, needsReextract)
        }

        return Reconciliation(
            data = newData,
            corrections = corrections,
            unresolvedDetails = unresolved,
            candidatesForReextraction = needsReextract.toList()
        )
    }

    /**
     * Tente de resoudre une incoherence HT+TVA != TTC en s'appuyant sur
     * `tauxTVA`. Si HT*taux/100 ≈ TVA (dans la tolerance), HT et TVA sont
     * coherents et c'est TTC qui a ete mal lu : on recalcule TTC = HT+TVA.
     * Inversement, si TTC*taux/(100+taux) ≈ TVA, c'est HT qui est faux :
     * on ne corrige PAS automatiquement (trop fragile, pourrait cacher une
     * erreur metier) mais on signale le candidat.
     *
     * Retourne true si une correction a pu etre appliquee.
     */
    private fun tryResolveByTaux(
        ht: BigDecimal,
        tva: BigDecimal,
        ttc: BigDecimal,
        taux: BigDecimal?,
        names: FieldNames,
        newData: MutableMap<String, Any?>,
        corrections: MutableList<Correction>
    ): Boolean {
        if (taux == null || taux !in tauxLegaux) return false

        // Test 1 : HT*taux/100 ≈ TVA -> HT et TVA OK, TTC faux.
        val expectedTvaFromHt = ht.multiply(taux).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        val tvaCoherente = (expectedTvaFromHt - tva).abs() <= tolerance(tva.max(BigDecimal.ONE))
        if (tvaCoherente) {
            val expectedTtc = ht.add(tva)
            newData[names.ttc] = expectedTtc
            corrections += Correction(
                names.ttc, ttc, expectedTtc,
                "${names.ht}*${taux.toPlainString()}%=${expectedTvaFromHt.setScale(2)} coherent avec ${names.tva}=${tva.setScale(2)} -> ${names.ttc} recompute=HT+TVA"
            )
            return true
        }

        // Pas resolvable par taux : on laisse a la regle R16 + revue humaine.
        return false
    }

    /**
     * Tolerance hybride : max(absolu, base*pct). Reproduit le contrat de
     * `app.tolerance-montant` + `app.tolerance-montant-pct` consomme par
     * ValidationEngine. Sur une facture a 12 000 MAD, limite = max(0.05, 60) = 60.
     */
    private fun tolerance(base: BigDecimal): BigDecimal {
        val absLimit = tolAbs
        val pctLimit = base.abs().multiply(tolPct)
        return absLimit.max(pctLimit)
    }

    private fun appendWarnings(
        newData: MutableMap<String, Any?>,
        corrections: List<Correction>,
        unresolved: List<String>
    ) {
        val existing = (newData["_warnings"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val correctionWarnings = corrections.map { c ->
            val before = c.before?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "(absent)"
            val after = c.after.setScale(2, RoundingMode.HALF_UP).toPlainString()
            "Reconciliation arithmetique [${c.field}]: ${c.reason} (${before} -> ${after})"
        }
        val unresolvedWarnings = unresolved.map { "Incoherence arithmetique non resolue: $it" }
        newData["_warnings"] = existing + correctionWarnings + unresolvedWarnings
    }

    /**
     * Conversion d'un champ "Number ou String" en BigDecimal robuste aux
     * formats FR/EN. Identique a la logique de ExtractionQualityService
     * pour preserver une coherence stricte sur la lecture des montants.
     */
    private fun numericField(data: Map<String, Any?>, key: String): BigDecimal? {
        val v = data.getFieldCaseInsensitive(key) ?: return null
        return when (v) {
            is Number -> BigDecimal(v.toString())
            is String -> v.replace("[^\\d.,\\-]".toRegex(), "").let { s ->
                if (s.isEmpty()) return null
                val lc = s.lastIndexOf(',')
                val ld = s.lastIndexOf('.')
                if (lc > ld) s.replace(".", "").replace(",", ".").toBigDecimalOrNull()
                else s.replace(",", "").toBigDecimalOrNull()
            }
            else -> null
        }
    }
}
