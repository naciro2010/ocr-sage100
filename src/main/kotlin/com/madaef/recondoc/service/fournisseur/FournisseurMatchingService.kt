package com.madaef.recondoc.service.fournisseur

import com.madaef.recondoc.entity.dossier.FournisseurAlias
import com.madaef.recondoc.entity.dossier.FournisseurCanonique
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.repository.dossier.FournisseurAliasRepository
import com.madaef.recondoc.repository.dossier.FournisseurCanoniqueRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDateTime

data class MatchResult(
    val canonique: FournisseurCanonique,
    val similarity: Double,
    val isExact: Boolean,
    val isNew: Boolean,
    val requiresReview: Boolean
)

@Service
class FournisseurMatchingService(
    private val canoniqueRepo: FournisseurCanoniqueRepository,
    private val aliasRepo: FournisseurAliasRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
        private val LEGAL_SUFFIXES = Regex("\\b(sarl|sas|sa|sprl|eurl|sci|gie)\\b")
        private val NON_ALPHANUM = Regex("[^a-z0-9\\s]")
        private val MULTIPLE_SPACES = Regex("\\s+")

        /** Au-dessus : considéré comme exact match, rattaché sans warning humain. */
        private const val EXACT_MATCH_THRESHOLD = 0.97
        /** Entre ce seuil et EXACT : rattaché mais marqué pour revue humaine. */
        private const val REVIEW_THRESHOLD = 0.82

        /** Source la plus crédible = celle qui impose son orthographe canonique. */
        private val CREDIBILITY: Map<TypeDocument, Int> = mapOf(
            TypeDocument.FACTURE to 100,
            TypeDocument.BON_COMMANDE to 90,
            TypeDocument.CONTRAT_AVENANT to 85,
            TypeDocument.ATTESTATION_FISCALE to 80,
            TypeDocument.ORDRE_PAIEMENT to 60,
            TypeDocument.PV_RECEPTION to 40,
            TypeDocument.CHECKLIST_AUTOCONTROLE to 30,
            TypeDocument.TABLEAU_CONTROLE to 30,
            TypeDocument.FORMULAIRE_FOURNISSEUR to 70,
            TypeDocument.CHECKLIST_PIECES to 20,
            TypeDocument.INCONNU to 10
        )
    }

    fun normalize(raw: String): String {
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .replace(".", "")
            .replace("-", " ")
            .replace(NON_ALPHANUM, " ")
            .replace(LEGAL_SUFFIXES, " ")
            .replace(MULTIPLE_SPACES, " ")
            .trim()
    }

    /**
     * Jaro-Winkler avec bonus de containment (substring). Une chaîne courte
     * totalement contenue dans une chaîne longue (ex: "force emploi" dans
     * "maroc force emploi") obtient un bonus pour éviter le faux négatif.
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val jw = jaroWinkler(a, b)
        val containment = containmentBonus(a, b)
        return maxOf(jw, containment).coerceIn(0.0, 1.0)
    }

    @Transactional
    fun findOrCreateCanonical(
        rawName: String,
        sourceType: TypeDocument,
        ice: String? = null,
        identifiantFiscal: String? = null,
        rib: String? = null
    ): MatchResult {
        val trimmed = rawName.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("rawName vide")

        val normalized = normalize(trimmed)

        iceMatch(ice)?.let { return enrichAndReturn(it, trimmed, normalized, sourceType, 1.0, isExact = true, review = false) }

        canoniqueRepo.findByNomNormalise(normalized)?.let {
            return enrichAndReturn(it, trimmed, normalized, sourceType, 1.0, isExact = true, review = false)
        }

        aliasRepo.findByNomNormalise(normalized)?.let {
            return enrichAndReturn(it.canonique, trimmed, normalized, sourceType, 1.0, isExact = true, review = false)
        }

        val candidates = canoniqueRepo.findAllOrderedByDerniereUtilisation()
        var bestScore = 0.0
        var best: FournisseurCanonique? = null
        for (c in candidates) {
            val score = similarity(normalized, c.nomNormalise)
            if (score > bestScore) { bestScore = score; best = c }
            if (score >= EXACT_MATCH_THRESHOLD) break
        }

        return when {
            best != null && bestScore >= EXACT_MATCH_THRESHOLD ->
                enrichAndReturn(best, trimmed, normalized, sourceType, bestScore, isExact = true, review = false)

            best != null && bestScore >= REVIEW_THRESHOLD -> {
                log.warn("Fournisseur similaire detecte: '{}' ~ '{}' (score={}), rattache avec demande de revue",
                    trimmed, best.nomCanonique, bestScore)
                enrichAndReturn(best, trimmed, normalized, sourceType, bestScore, isExact = false, review = true)
            }

            else -> {
                val newCanonique = canoniqueRepo.save(FournisseurCanonique(
                    nomCanonique = trimmed,
                    nomNormalise = normalized,
                    sourceTypeDocument = sourceType.name,
                    ice = ice?.takeIf { it.isNotBlank() },
                    identifiantFiscal = identifiantFiscal?.takeIf { it.isNotBlank() },
                    rib = rib?.takeIf { it.isNotBlank() }
                ))
                aliasRepo.save(FournisseurAlias(
                    canonique = newCanonique, nomBrut = trimmed, nomNormalise = normalized,
                    sourceTypeDocument = sourceType.name, similarityScore = null, requiresReview = false
                ))
                MatchResult(newCanonique, 1.0, isExact = true, isNew = true, requiresReview = false)
            }
        }
    }

    private fun iceMatch(ice: String?): FournisseurCanonique? {
        val cleaned = ice?.replace(Regex("[^\\d]"), "")?.takeIf { it.length == 15 } ?: return null
        return canoniqueRepo.findByIce(cleaned)
    }

    private fun enrichAndReturn(
        canonique: FournisseurCanonique,
        rawName: String,
        normalized: String,
        sourceType: TypeDocument,
        score: Double,
        isExact: Boolean,
        review: Boolean
    ): MatchResult {
        val existingAlias = aliasRepo.findByNomNormalise(normalized)
        if (existingAlias == null) {
            aliasRepo.save(FournisseurAlias(
                canonique = canonique, nomBrut = rawName, nomNormalise = normalized,
                sourceTypeDocument = sourceType.name,
                similarityScore = BigDecimal(score).setScale(3, RoundingMode.HALF_UP),
                requiresReview = review
            ))
        }

        val currentRank = CREDIBILITY[runCatching { TypeDocument.valueOf(canonique.sourceTypeDocument) }.getOrNull() ?: TypeDocument.INCONNU] ?: 0
        val incomingRank = CREDIBILITY[sourceType] ?: 0
        if (incomingRank > currentRank) {
            canonique.nomCanonique = rawName
            canonique.sourceTypeDocument = sourceType.name
        }
        canonique.dateMiseAJour = LocalDateTime.now()
        canoniqueRepo.save(canonique)

        return MatchResult(canonique, score, isExact, isNew = false, requiresReview = review)
    }

    private fun jaroWinkler(s1: String, s2: String): Double {
        val m = matchingCount(s1, s2)
        if (m == 0) return 0.0
        val t = transpositions(s1, s2).toDouble() / 2.0
        val jaro = (m / s1.length.toDouble() + m / s2.length.toDouble() + (m - t) / m) / 3.0

        val prefix = s1.zip(s2).takeWhile { (a, b) -> a == b }.size.coerceAtMost(4)
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    private fun matchingCount(s1: String, s2: String): Int {
        val maxDist = (maxOf(s1.length, s2.length) / 2) - 1
        val matched1 = BooleanArray(s1.length)
        val matched2 = BooleanArray(s2.length)
        var count = 0
        for (i in s1.indices) {
            val start = maxOf(0, i - maxDist)
            val end = minOf(s2.length - 1, i + maxDist)
            for (j in start..end) {
                if (matched2[j]) continue
                if (s1[i] != s2[j]) continue
                matched1[i] = true; matched2[j] = true; count++; break
            }
        }
        return count
    }

    private fun transpositions(s1: String, s2: String): Int {
        val maxDist = (maxOf(s1.length, s2.length) / 2) - 1
        val matched1 = BooleanArray(s1.length)
        val matched2 = BooleanArray(s2.length)
        for (i in s1.indices) {
            val start = maxOf(0, i - maxDist)
            val end = minOf(s2.length - 1, i + maxDist)
            for (j in start..end) {
                if (matched2[j] || s1[i] != s2[j]) continue
                matched1[i] = true; matched2[j] = true; break
            }
        }
        val ms1 = s1.withIndex().filter { matched1[it.index] }.map { it.value }
        val ms2 = s2.withIndex().filter { matched2[it.index] }.map { it.value }
        return ms1.zip(ms2).count { (a, b) -> a != b }
    }

    private fun containmentBonus(a: String, b: String): Double {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length < 4) return 0.0
        if (!long.contains(short)) return 0.0
        val ratio = short.length.toDouble() / long.length
        return 0.80 + (ratio * 0.15)
    }
}
