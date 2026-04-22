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

    private val checks: Map<TypeDocument, List<Check>> = mapOf(
        TypeDocument.FACTURE to listOf(
            Check("ice", Kind.DIGITS, critical = true, minLen = 10),
            Check("rib", Kind.DIGITS, critical = false, minLen = 12),
            Check("identifiantFiscal", Kind.DIGITS, critical = false, minLen = 5),
            Check("numeroFacture", Kind.ALNUM, critical = true, minLen = 4)
        ),
        TypeDocument.BON_COMMANDE to listOf(
            Check("reference", Kind.ALNUM, critical = true, minLen = 4)
        ),
        TypeDocument.ORDRE_PAIEMENT to listOf(
            Check("numeroOp", Kind.ALNUM, critical = true, minLen = 4),
            Check("rib", Kind.DIGITS, critical = false, minLen = 12),
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
        )
    )

    fun validate(type: TypeDocument, data: Map<String, Any?>, rawText: String): GroundingResult {
        val typeChecks = checks[type] ?: return GroundingResult(true, emptyList(), data)
        val violations = mutableListOf<FieldViolation>()
        val cleaned = data.toMutableMap()
        var hasCritical = false

        val digitsText by lazy { OcrConfusions.applyDigitConfusions(rawText) }
        val alnumText by lazy { normalizeAlnum(rawText) }

        for (check in typeChecks) {
            val raw = data[check.field] ?: data.entries
                .firstOrNull { it.key.equals(check.field, ignoreCase = true) }?.value
            val value = raw?.toString()?.trim()
            if (value.isNullOrEmpty()) continue

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
