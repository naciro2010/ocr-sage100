package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.service.AppSettingsService
import org.springframework.stereotype.Service

/**
 * Masque les PII (emails, telephones MA, noms de personnes precedes d'une
 * civilite) avant tout envoi du texte a Claude (USA).
 *
 * Objectif : conformite Loi 09-08 / CNDP (souverainete Maroc) + RGPD. Les
 * identifiants B2B (ICE, IF, RC, CNSS, RIB) et les raisons sociales
 * d'entreprise ne sont volontairement PAS masques car :
 *   - l'ICE est un registre public marocain
 *   - IF/RC/CNSS sont des identifiants d'entite juridique, pas de personne
 *     physique
 *   - le RIB d'entreprise est un compte bancaire B2B, pas une donnee a
 *     caractere personnel au sens Loi 09-08 art. 1 (qui vise une "personne
 *     physique identifiee ou identifiable"). Masquer le RIB casse en plus
 *     l'extraction (Claude ne peut plus distinguer RIB beneficiaire vs
 *     RIB ordonnateur quand un OP en cite plusieurs) et la self-consistency
 *     (run2 ne peut pas reconfirmer un identifiant qu'il voit comme un
 *     token opaque). OBJECTIF #1 fiabilite 100% > masquage cosmetique.
 *   - les raisons sociales fournisseur sont des identifiants business
 *     necessaires aux regles de validation metier (R14)
 *
 * Le mapping est retourne inline par tokenize() : il n'est jamais persiste
 * et vit uniquement le temps d'une extraction. Apres reception du JSON
 * Claude, detokenize() restaure les valeurs reelles AVANT l'ecriture en
 * base. L'app stocke donc toujours les valeurs non masquees cote Maroc ;
 * seul le trajet vers Anthropic est pseudonymise.
 */
@Service
class PseudonymizationService(
    private val appSettingsService: AppSettingsService? = null
) {

    fun isEnabled(): Boolean = appSettingsService?.isPseudonymizationEnabled() ?: true

    data class PiiMapping(
        val forward: Map<String, String>,
        val backward: Map<String, String>
    ) {
        val isEmpty: Boolean get() = backward.isEmpty()

        companion object {
            val EMPTY = PiiMapping(emptyMap(), emptyMap())
        }
    }

    enum class PiiKind(val prefix: String) {
        EMAIL("EMAIL"),
        PHONE("PHONE"),
        PERSON("PERSON")
    }

    fun tokenize(text: String): Pair<String, PiiMapping> {
        if (!isEnabled()) return text to PiiMapping.EMPTY
        return tokenizeWith(text, PiiMapping.EMPTY)
    }

    /**
     * Fusionne avec un mapping existant pour garantir la stabilite des
     * tokens entre plusieurs documents d'un meme dossier : le meme email
     * vu dans deux documents recevra le meme token, ce qui preserve les
     * regles de coherence cross-documents (R09-R11, R14).
     */
    fun tokenizeWith(text: String, existing: PiiMapping): Pair<String, PiiMapping> {
        if (!isEnabled()) return text to existing
        if (text.isEmpty()) return text to existing

        val forward = existing.forward.toMutableMap()
        val backward = existing.backward.toMutableMap()
        val counters = PiiKind.values().associateWith { kind ->
            backward.keys.count { it.startsWith("[${kind.prefix}_") }
        }.toMutableMap()

        var current = text
        // Ordre : Email -> Phone -> Person.
        for (kind in PiiKind.values()) {
            current = maskKind(current, kind, forward, backward, counters)
        }
        return current to PiiMapping(forward.toMap(), backward.toMap())
    }

    private fun maskKind(
        text: String,
        kind: PiiKind,
        forward: MutableMap<String, String>,
        backward: MutableMap<String, String>,
        counters: MutableMap<PiiKind, Int>
    ): String = patternFor(kind).replace(text) { match ->
        val real = match.value
        forward[real] ?: run {
            counters[kind] = (counters[kind] ?: 0) + 1
            val token = "[${kind.prefix}_${counters[kind]}]"
            forward[real] = token
            backward[token] = real
            token
        }
    }

    private fun patternFor(kind: PiiKind): Regex = when (kind) {
        PiiKind.EMAIL -> EMAIL_RE
        PiiKind.PHONE -> PHONE_MA_RE
        PiiKind.PERSON -> PERSON_RE
    }

    /**
     * Detokenize : restaure recursivement les tokens dans un String, une
     * Map (donneesExtraites typique) ou une List (lignes de facture).
     */
    fun detokenize(value: Any?, mapping: PiiMapping): Any? {
        if (mapping.isEmpty) return value
        return when (value) {
            is String -> detokenizeString(value, mapping.backward)
            is Map<*, *> -> value.mapValues { (_, v) -> detokenize(v, mapping) }
            is List<*> -> value.map { detokenize(it, mapping) }
            else -> value
        }
    }

    private fun detokenizeString(s: String, backward: Map<String, String>): String {
        if (!s.contains('[')) return s
        var result = s
        for ((token, real) in backward) {
            if (result.contains(token)) {
                result = result.replace(token, real)
            }
        }
        return result
    }

    companion object {
        private val EMAIL_RE = Regex("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b")

        // Telephone Maroc :
        //   - mobile/fixe : +212 suivi de 5/6/7 puis 8 chiffres (avec separateurs
        //     optionnels espace/tiret/point)
        //   - forme locale : 0 suivi de 5/6/7 puis 8 chiffres
        private val PHONE_MA_RE = Regex(
            "(?<![\\d])(?:\\+212[-.\\s]?|0)[567](?:[-.\\s]?\\d){8}(?![\\d])"
        )

        // Civilite + nom(s) propre(s). Capture une a quatre composantes de nom
        // (nom compose, nom + prenom). Accepte les accents francais courants
        // et les apostrophes (d'Auvergne, El-Amrani).
        private val PERSON_RE = Regex(
            "\\b(?:M\\.|Mme\\.?|Mlle\\.?|Monsieur|Madame|Mademoiselle)\\s+" +
                "[A-ZÉÈÀÂÎÔÛÇ][a-zéèêàâîïôûçA-ZÉÈÀÂÎÔÛÇ'\\-]+" +
                "(?:\\s+[A-ZÉÈÀÂÎÔÛÇ][a-zéèêàâîïôûçA-ZÉÈÀÂÎÔÛÇ'\\-]+){0,3}"
        )
    }
}
