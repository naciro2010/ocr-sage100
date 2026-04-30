package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.OcrConfusions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Verifie que les identifiants critiques extraits par Claude apparaissent
 * reellement dans le texte OCR. Anti-hallucination : si le scan de l'ICE est
 * illisible, Claude peut inventer une valeur plausible respectant le regex
 * `ExtractionSchemaValidator` (15 chiffres) — ce validator detecte l'absence
 * de la valeur dans le texte source et la strip, forcant une revue humaine.
 *
 * Strategie :
 *  - champs numeriques (ICE/RIB/IF) : normalisation OCR (O->0, l->1) puis
 *    recherche d'au moins 10 chiffres contigus dans le texte normalise.
 *  - champs alphanumeriques (numero facture/OP/contrat) : normalisation
 *    aggressive (lower + strip ponctuation/espaces) puis recherche substring.
 *  - les valeurs absentes du texte sont strip a null et ajoutees aux
 *    `_warnings` avec un message explicite.
 *
 * Ne s'occupe PAS des dates et montants : leurs formats varient trop (ISO vs
 * FR vs separateurs d'OCR) pour qu'un substring check soit fiable sans
 * introduire des faux positifs.
 */
@Service
class GroundingValidator {
    private val log = LoggerFactory.getLogger(javaClass)

    data class GroundingResult(
        val valid: Boolean,
        val violations: List<FieldViolation>,
        val cleanedData: Map<String, Any?>
    )

    private enum class Kind { DIGITS, ALNUM }

    private data class Check(
        val field: String,
        val kind: Kind,
        /** Critique = le champ ne DOIT pas etre hallucine ; strip + violation critique. */
        val critical: Boolean,
        /** Longueur minimale du substring de reference a chercher. */
        val minLen: Int
    )

    // RIB Maroc = 24 chiffres exacts. minLen=20 chiffres (compte + cle + une marge
    // OCR sur le code banque) reduit la collision quasi-garantie de l'ancien
    // minLen=12 : un document qui cite plusieurs RIBs partage souvent le code
    // banque (premiers chiffres) ET la cle (derniers chiffres). Avec 12 derniers
    // chiffres, Claude peut composer un RIB hybride (debut RIB-A + fin RIB-B)
    // qui passe le grounding parce que les 12 derniers correspondent a RIB-B.
    // Avec 20 chiffres exiges, l'hybride est detecte (les 20 derniers ne
    // correspondent a aucun RIB pur du texte).
    private val ribMinLen = 20

    private val checks: Map<TypeDocument, List<Check>> = mapOf(
        TypeDocument.FACTURE to listOf(
            Check("ice", Kind.DIGITS, critical = true, minLen = 10),
            Check("rib", Kind.DIGITS, critical = false, minLen = ribMinLen),
            Check("identifiantFiscal", Kind.DIGITS, critical = false, minLen = 5),
            Check("numeroFacture", Kind.ALNUM, critical = true, minLen = 4)
        ),
        TypeDocument.BON_COMMANDE to listOf(
            Check("reference", Kind.ALNUM, critical = true, minLen = 4)
        ),
        TypeDocument.ORDRE_PAIEMENT to listOf(
            Check("numeroOp", Kind.ALNUM, critical = true, minLen = 4),
            Check("rib", Kind.DIGITS, critical = false, minLen = ribMinLen),
            Check("referenceFacture", Kind.ALNUM, critical = false, minLen = 4)
        ),
        TypeDocument.CONTRAT_AVENANT to listOf(
            Check("referenceContrat", Kind.ALNUM, critical = true, minLen = 3)
        ),
        TypeDocument.ATTESTATION_FISCALE to listOf(
            Check("numero", Kind.ALNUM, critical = true, minLen = 3),
            Check("ice", Kind.DIGITS, critical = false, minLen = 10),
            Check("identifiantFiscal", Kind.DIGITS, critical = false, minLen = 5)
        ),
        TypeDocument.PV_RECEPTION to listOf(
            Check("referenceContrat", Kind.ALNUM, critical = false, minLen = 3)
        ),
        TypeDocument.CHECKLIST_AUTOCONTROLE to listOf(
            Check("referenceFacture", Kind.ALNUM, critical = true, minLen = 4)
        ),
        TypeDocument.CHECKLIST_PIECES to listOf(
            Check("referenceFacture", Kind.ALNUM, critical = true, minLen = 4)
        ),
        TypeDocument.TABLEAU_CONTROLE to listOf(
            Check("referenceFacture", Kind.ALNUM, critical = true, minLen = 4)
        ),
        // Couche engagement : montants eleves, hallucination sur la reference
        // marche/BC cadre/contrat = engagement comptable Sage errone.
        TypeDocument.MARCHE to listOf(
            Check("reference", Kind.ALNUM, critical = true, minLen = 3),
            Check("numeroAo", Kind.ALNUM, critical = false, minLen = 3)
        ),
        TypeDocument.BON_COMMANDE_CADRE to listOf(
            Check("reference", Kind.ALNUM, critical = true, minLen = 3)
        ),
        TypeDocument.CONTRAT_CADRE to listOf(
            Check("reference", Kind.ALNUM, critical = true, minLen = 3)
        )
    )

    fun validate(type: TypeDocument, data: Map<String, Any?>, rawText: String): GroundingResult {
        val typeChecks = checks[type] ?: return GroundingResult(true, emptyList(), data)
        val violations = mutableListOf<FieldViolation>()
        val cleaned = data.toMutableMap()
        var hasCritical = false

        val digitsText by lazy { OcrConfusions.applyDigitConfusions(rawText) }
        val alnumText by lazy { normalizeAlnum(rawText) }
        val normalizedHaystack by lazy { normalizeForQuote(rawText) }

        // Couche 1 : citations source_quote fournies par Claude. Si une valeur
        // est extraite mais sa citation est introuvable dans le texte source,
        // on traite le champ comme hallucine (strip + violation).
        val criticalFieldNames = typeChecks.filter { it.critical }.map { it.field }.toSet()
        val quoteValidation = validateSourceQuotes(data, normalizedHaystack, criticalFieldNames)
        for ((field, reason) in quoteValidation.failures) {
            // Ne strip que si une valeur a ete extraite : un champ deja null
            // ne necessite pas de violation supplementaire.
            if (cleaned.getFieldCaseInsensitive(field) != null) {
                violations += FieldViolation(field, cleaned.getFieldCaseInsensitive(field)?.toString() ?: "", reason)
                cleaned[field] = null
                if (field in criticalFieldNames) hasCritical = true
                log.warn("SourceQuote grounding violation on {}.{}: {}", type, field, reason)
            }
        }

        for (check in typeChecks) {
            val value = data.getFieldCaseInsensitive(check.field)?.toString()?.trim()
            if (value.isNullOrEmpty()) continue
            // Si la couche citation a deja strip ce champ, ne pas re-violer dessus.
            if (cleaned.getFieldCaseInsensitive(check.field) == null) continue

            val grounded = when (check.kind) {
                Kind.DIGITS -> groundDigits(value, digitsText, check.minLen)
                Kind.ALNUM -> groundAlnum(value, alnumText, check.minLen)
            }

            if (!grounded) {
                violations += FieldViolation(
                    check.field, value,
                    "valeur absente du texte OCR (possible hallucination)"
                )
                cleaned[check.field] = null
                if (check.critical) hasCritical = true
                log.warn("Grounding violation on {}.{}: value '{}' not found in OCR text",
                    type, check.field, value)
            }
        }

        if (violations.isNotEmpty()) {
            val existing = (data["_warnings"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val added = violations.map {
                "Grounding violation on ${it.field}: ${it.reason} (value='${it.value}')"
            }
            cleaned["_warnings"] = existing + added
        }

        return GroundingResult(
            valid = !hasCritical,
            violations = violations,
            cleanedData = cleaned
        )
    }

    private data class QuoteValidation(val failures: List<Pair<String, String>>)

    /**
     * Verifie les citations `_sourceQuotes` fournies par Claude. Format attendu :
     * `[ {"field":"ice","quote":"ICE : 001 509 176 000 008"}, ... ]`.
     *
     * Une citation est valide si, apres normalisation (lowercase + suppression
     * espaces/ponctuation), elle est une sous-chaine du texte OCR normalise.
     * Tolerance OCR : 1 caractere de difference accepte sur les citations >= 10
     * caracteres (Levenshtein <= 1) pour absorber les confusions O/0, l/1.
     *
     * Si la citation ne correspond a rien dans le texte source, le champ est
     * considere comme hallucine et sera strip par le caller. Pas de citation
     * pour un champ critique extrait = violation aussi (Claude doit citer ses
     * sources).
     */
    private fun validateSourceQuotes(
        data: Map<String, Any?>,
        normalizedHaystack: String,
        criticalFields: Set<String>
    ): QuoteValidation {
        val failures = mutableListOf<Pair<String, String>>()
        val rawQuotes = data["_sourceQuotes"] as? List<*> ?: return QuoteValidation(emptyList())

        // 1. Citations fournies : verifier qu'elles existent dans le texte source.
        val citedFields = mutableSetOf<String>()
        for (entry in rawQuotes) {
            val map = entry as? Map<*, *> ?: continue
            val field = map["field"]?.toString()?.trim().orEmpty()
            val quote = map["quote"]?.toString()?.trim().orEmpty()
            if (field.isEmpty() || quote.isEmpty()) continue
            citedFields += field
            val normalizedQuote = normalizeForQuote(quote)
            if (normalizedQuote.length < 5) continue // citation trop courte pour etre fiable
            val matches = normalizedHaystack.contains(normalizedQuote) ||
                (normalizedQuote.length >= 10 && containsWithFuzz(normalizedHaystack, normalizedQuote, maxEdits = 1))
            if (!matches) {
                failures += field to "citation source absente du texte OCR (quote='${quote.take(60)}')"
            }
        }

        // 2. Champ critique extrait sans citation : exiger la preuve. Le schema
        // l'impose deja dans le prompt, mais on durcit cote serveur pour bloquer
        // les regressions silencieuses (cache stale, prompt cassé).
        for (field in criticalFields) {
            val value = data.getFieldCaseInsensitive(field)
            if (value != null && value.toString().isNotBlank() && field !in citedFields) {
                failures += field to "champ critique extrait sans _sourceQuote (preuve manquante)"
            }
        }

        return QuoteValidation(failures)
    }

    /**
     * Normalisation pour la comparaison de citations : lowercase, suppression
     * des espaces, tirets, points, virgules, slashs, sauts de ligne. Preserve
     * les caracteres alphanumeriques et accentues.
     */
    private fun normalizeForQuote(s: String): String =
        s.lowercase().replace(Regex("[\\s\\-.,/:;'\"()\\[\\]]+"), "")

    /**
     * Recherche fuzzy : la citation est consideree presente si une fenetre de
     * la meme longueur dans le haystack a une distance d'edition <= maxEdits.
     * Implementation simple O(n*m) suffisante pour des citations <= 80 chars.
     */
    private fun containsWithFuzz(haystack: String, needle: String, maxEdits: Int): Boolean {
        if (needle.length > haystack.length) return false
        val m = needle.length
        for (start in 0..haystack.length - m) {
            var diffs = 0
            for (i in 0 until m) {
                if (haystack[start + i] != needle[i]) {
                    diffs++
                    if (diffs > maxEdits) break
                }
            }
            if (diffs <= maxEdits) return true
        }
        return false
    }

    private fun groundDigits(value: String, normalizedText: String, minLen: Int): Boolean {
        val digits = OcrConfusions.digitsOnlyWithConfusions(value)
        if (digits.length < minLen) return true // valeur trop courte pour etre fiable
        // On cherche les minLen derniers chiffres (le debut peut etre un prefixe
        // normalise par Claude alors que le texte OCR a des espaces/tirets).
        val needle = digits.takeLast(minLen)
        val haystack = digitsOnly(normalizedText)
        return haystack.contains(needle)
    }

    private fun groundAlnum(value: String, normalizedText: String, minLen: Int): Boolean {
        val norm = normalizeAlnum(value)
        if (norm.length < minLen) return true
        return normalizedText.contains(norm)
    }

    private fun normalizeAlnum(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun digitsOnly(s: String): String =
        s.replace(Regex("[^0-9]"), "")
}
