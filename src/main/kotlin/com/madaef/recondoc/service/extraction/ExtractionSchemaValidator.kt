package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.entity.dossier.TypeDocument
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class FieldViolation(val field: String, val value: String?, val reason: String)

data class SchemaValidationResult(
    val valid: Boolean,
    val violations: List<FieldViolation>,
    val cleanedData: Map<String, Any?>
) {
    val severity: Int get() = violations.size
}

private enum class FieldKind { ICE, RIB, IF_NUM, RC, DATE, MONTANT_POSITIF, TAUX_TVA, NON_VIDE }

private data class FieldRule(
    val name: String,
    val kind: FieldKind,
    val critical: Boolean = true
)

@Service
class ExtractionSchemaValidator {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val ICE_RE = Regex("^\\d{15}$")
        private val RIB_RE = Regex("^\\d{24}$")
        private val IF_RE = Regex("^\\d{5,15}$")
        private val RC_RE = Regex("^[A-Z0-9]{1,20}$")
        private val ISO_DATE_FORMATTERS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
        )
        private val ALLOWED_TVA = listOf(
            BigDecimal.ZERO, BigDecimal("7"), BigDecimal("10"), BigDecimal("14"), BigDecimal("20")
        )

        // Placeholders OCR frequemment generes par Claude quand il ne trouve pas
        // la donnee et ne met pas null. Ces valeurs doivent etre traitees comme
        // "champ absent" pour ne pas polluer les donnees metier.
        private val NON_VIDE_PLACEHOLDERS = setOf(
            "n/a", "na", "n.a", "n.a.", "nc", "null", "none",
            "inconnu", "inconnue", "unknown", "-", "--", "?", "??",
            "non renseigne", "non renseignee", "non communique",
            "non specifie", "non specifiee", "tbd"
        )
        private val PUNCTUATION_ONLY_RE = Regex("^[\\p{Punct}\\s]+$")
    }

    private val rules: Map<TypeDocument, List<FieldRule>> = mapOf(
        TypeDocument.FACTURE to listOf(
            FieldRule("ice", FieldKind.ICE),
            FieldRule("rib", FieldKind.RIB, critical = false),
            FieldRule("identifiantFiscal", FieldKind.IF_NUM, critical = false),
            FieldRule("dateFacture", FieldKind.DATE),
            FieldRule("montantTTC", FieldKind.MONTANT_POSITIF),
            FieldRule("montantHT", FieldKind.MONTANT_POSITIF, critical = false),
            FieldRule("montantTVA", FieldKind.MONTANT_POSITIF, critical = false),
            FieldRule("tauxTVA", FieldKind.TAUX_TVA, critical = false),
            FieldRule("numeroFacture", FieldKind.NON_VIDE),
            FieldRule("fournisseur", FieldKind.NON_VIDE)
        ),
        TypeDocument.BON_COMMANDE to listOf(
            FieldRule("dateBC", FieldKind.DATE, critical = false),
            FieldRule("montantTTC", FieldKind.MONTANT_POSITIF),
            FieldRule("fournisseur", FieldKind.NON_VIDE)
        ),
        TypeDocument.ORDRE_PAIEMENT to listOf(
            FieldRule("dateOP", FieldKind.DATE, critical = false),
            FieldRule("dateEmission", FieldKind.DATE, critical = false),
            FieldRule("montantOperation", FieldKind.MONTANT_POSITIF),
            FieldRule("rib", FieldKind.RIB, critical = false)
        ),
        TypeDocument.CONTRAT_AVENANT to listOf(
            FieldRule("dateContrat", FieldKind.DATE, critical = false),
            FieldRule("montantTotal", FieldKind.MONTANT_POSITIF, critical = false),
            FieldRule("fournisseur", FieldKind.NON_VIDE)
        ),
        TypeDocument.ATTESTATION_FISCALE to listOf(
            FieldRule("ice", FieldKind.ICE, critical = false),
            FieldRule("identifiantFiscal", FieldKind.IF_NUM, critical = false),
            FieldRule("dateEmission", FieldKind.DATE, critical = false),
            FieldRule("dateEdition", FieldKind.DATE, critical = false),
            FieldRule("dateValidite", FieldKind.DATE, critical = false)
        )
    )

    fun validate(type: TypeDocument, data: Map<String, Any?>): SchemaValidationResult {
        val typeRules = rules[type] ?: return SchemaValidationResult(true, emptyList(), data)
        val violations = mutableListOf<FieldViolation>()
        val cleaned = data.toMutableMap()
        var hasCriticalViolation = false

        for (rule in typeRules) {
            val rawValue = data[rule.name] ?: data.entries
                .firstOrNull { it.key.equals(rule.name, ignoreCase = true) }?.value
            val check = validateField(rule, rawValue)
            if (check != null) {
                violations += check
                if (rule.critical) hasCriticalViolation = true
                if (shouldStrip(rule.kind)) {
                    cleaned[rule.name] = null
                    log.warn("Stripping invalid field {} on {} (value={}): {}",
                        rule.name, type, rawValue, check.reason)
                }
            }
        }

        if (violations.isNotEmpty()) {
            val existingWarnings = (data["_warnings"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val newWarnings = existingWarnings + violations.map { "Schema violation on ${it.field}: ${it.reason}" }
            cleaned["_warnings"] = newWarnings
        }

        return SchemaValidationResult(
            valid = !hasCriticalViolation,
            violations = violations,
            cleanedData = cleaned
        )
    }

    private fun shouldStrip(kind: FieldKind): Boolean = when (kind) {
        FieldKind.NON_VIDE -> false
        else -> true
    }

    private fun validateField(rule: FieldRule, value: Any?): FieldViolation? {
        if (value == null) return null
        val str = value.toString().trim()
        if (str.isEmpty()) {
            return if (rule.kind == FieldKind.NON_VIDE)
                FieldViolation(rule.name, str, "champ obligatoire vide")
            else null
        }

        // NON_VIDE durci : on refuse aussi les placeholders ("N/A", "inconnu",
        // "?", "-"), les strings composees uniquement de ponctuation, et les
        // chaines < 2 caracteres significatifs. Un vrai numero ou nom metier
        // fait toujours >= 2 caracteres.
        if (rule.kind == FieldKind.NON_VIDE) {
            val lower = str.lowercase().trim()
            if (lower in NON_VIDE_PLACEHOLDERS) {
                return FieldViolation(rule.name, str, "valeur placeholder ('$str') refusee")
            }
            if (PUNCTUATION_ONLY_RE.matches(str)) {
                return FieldViolation(rule.name, str, "valeur composee uniquement de ponctuation")
            }
            if (str.length < 2) {
                return FieldViolation(rule.name, str, "valeur trop courte (< 2 caracteres)")
            }
        }

        return when (rule.kind) {
            FieldKind.ICE -> {
                val digits = normalizeDigits(str)
                if (ICE_RE.matches(digits)) null
                else FieldViolation(rule.name, str, "ICE attendu 15 chiffres, trouve ${digits.length}")
            }
            FieldKind.RIB -> {
                val digits = normalizeDigits(str)
                if (RIB_RE.matches(digits)) null
                else FieldViolation(rule.name, str, "RIB attendu 24 chiffres, trouve ${digits.length}")
            }
            FieldKind.IF_NUM -> {
                val digits = normalizeDigits(str)
                if (IF_RE.matches(digits)) null
                else FieldViolation(rule.name, str, "IF attendu 5-15 chiffres")
            }
            FieldKind.RC -> {
                if (RC_RE.matches(str.uppercase())) null
                else FieldViolation(rule.name, str, "RC format alphanumerique attendu")
            }
            FieldKind.DATE -> {
                if (tryParseDate(str) != null) null
                else FieldViolation(rule.name, str, "date non parseable (ISO ou dd/MM/yyyy)")
            }
            FieldKind.MONTANT_POSITIF -> {
                val bd = tryParseAmount(str)
                when {
                    bd == null -> FieldViolation(rule.name, str, "montant non numerique")
                    bd.signum() < 0 -> FieldViolation(rule.name, str, "montant negatif")
                    else -> null
                }
            }
            FieldKind.TAUX_TVA -> {
                val bd = tryParseAmount(str)
                when {
                    bd == null -> FieldViolation(rule.name, str, "taux TVA non numerique")
                    bd !in ALLOWED_TVA -> FieldViolation(rule.name, str, "taux TVA Maroc: 0, 7, 10, 14, 20 attendus")
                    else -> null
                }
            }
            FieldKind.NON_VIDE -> null
        }
    }

    /**
     * Filet de normalisation OCR sur les champs 100% numeriques (ICE/RIB/IF).
     * L'OCR confond frequemment des lettres avec des chiffres visuellement
     * proches : O/o -> 0, l/I -> 1. CLAUDE.md demande explicitement au prompt
     * de corriger ces cas ; on double la securite cote Kotlin pour ne pas
     * dependre uniquement du LLM. On ne normalise PAS les champs textuels
     * (NON_VIDE) pour ne pas alterer un nom ou un numero structure.
     */
    private fun normalizeDigits(s: String): String {
        val normalized = StringBuilder(s.length)
        for (c in s) {
            normalized.append(when (c) {
                'O', 'o' -> '0'
                'l', 'I' -> '1'
                else -> c
            })
        }
        return normalized.toString().replace("[^\\d]".toRegex(), "")
    }

    private fun tryParseDate(s: String): LocalDate? {
        for (f in ISO_DATE_FORMATTERS) {
            try { return LocalDate.parse(s, f) } catch (_: DateTimeParseException) {}
        }
        return null
    }

    private fun tryParseAmount(s: String): BigDecimal? {
        val cleaned = s.replace("[^\\d.,\\-]".toRegex(), "")
        if (cleaned.isEmpty()) return null
        val lc = cleaned.lastIndexOf(','); val ld = cleaned.lastIndexOf('.')
        return if (lc > ld) cleaned.replace(".", "").replace(",", ".").toBigDecimalOrNull()
        else cleaned.replace(",", "").toBigDecimalOrNull()
    }
}
