package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.LigneFacture
import java.math.BigDecimal

/**
 * Representation minimale d'une ligne de BC / grille tarifaire, prete a etre
 * rapprochee d'une [LigneFacture]. Les champs null signifient "pas d'info".
 */
data class BcLigne(
    val codeArticle: String?,
    val designation: String?,
    val quantite: BigDecimal?,
    val prixUnitaireHt: BigDecimal?,
    val montantHt: BigDecimal?
)

/** Parse les lignes brutes de `Document.donneesExtraites["lignes"]` en [BcLigne]. */
fun parseBcLignes(doc: Document?): List<BcLigne> {
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

/**
 * Trouve la meilleure ligne BC correspondant a une ligne facture.
 * Strategie :
 *  1. Match exact sur `codeArticle` si present des deux cotes.
 *  2. Sinon, meilleur score Jaccard sur la designation (seuil minimum 0.60).
 * Retourne `null` si aucune correspondance fiable.
 */
fun findBestRefMatch(
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
