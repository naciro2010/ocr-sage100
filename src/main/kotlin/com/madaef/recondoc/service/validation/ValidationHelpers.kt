package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.ResultatValidation
import com.madaef.recondoc.entity.dossier.StatutCheck
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.entity.dossier.ValidationEvidence
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Helpers deterministes partages par toutes les regles de validation.
 * Fonctions top-level pures : pas d'etat, pas d'injection Spring, facilement testables.
 *
 * Ces helpers vivaient auparavant comme methodes privees de ValidationEngine.
 * Les extraire permet de decouper le moteur en regles independantes tout en
 * conservant une seule source de verite pour les operations (montants, refs,
 * dates, normalisation d'identifiants B2B marocains).
 */

internal val WHITESPACE_RE = "[\\s\\-.]".toRegex()
private val REF_NORMALIZE_RE = "[\\s\\-_/.']+".toRegex()
private val CODE_NORMALIZE_RE = Regex("[\\s\\-_|/.]+")
private val NUMERIC_CLEAN_RE = "[^\\d.,\\-]".toRegex()
private val MONTH_NAMES = listOf(
    "janvier", "fevrier", "mars", "avril", "mai", "juin",
    "juillet", "aout", "septembre", "octobre", "novembre", "decembre"
)

private val TRUTHY = setOf("true", "oui", "conforme", "o", "yes")
private val FALSY = setOf("false", "non", "non conforme", "n", "no")

/** Normalise un RIB (24 chiffres) en retirant les espaces, tirets et points. */
fun normalizeRib(rib: String?): String? =
    rib?.replace(WHITESPACE_RE, "")?.takeIf { it.isNotBlank() }

/** Normalise un identifiant B2B (ICE/IF/RC/CNSS) : supprime espaces + zeros en tete. */
fun normalizeId(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return value.replace(WHITESPACE_RE, "").trimStart('0').ifEmpty { "0" }
}

/** Normalise un "code" (QR code, reference courte) pour comparaison souple. */
fun normalizeCode(code: String): String =
    code.trim().lowercase().replace(CODE_NORMALIZE_RE, "")

/** Compare deux references documentaires (BC/Contrat/Facture) tolerant a la ponctuation. */
fun matchReference(ref1: String?, ref2: String?): Boolean {
    if (ref1 == null || ref2 == null) return false
    val normalize = { s: String -> s.replace(REF_NORMALIZE_RE, "").trimStart('0').lowercase() }
    val n1 = normalize(ref1)
    val n2 = normalize(ref2)
    if (n1 == n2) return true
    val shorter = if (n1.length < n2.length) n1 else n2
    val longer = if (n1.length < n2.length) n2 else n1
    return shorter.length >= 4 && longer.contains(shorter)
}

/** Parseur tolerant : supporte ISO, DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY. */
fun parseLocalDate(s: String): LocalDate? {
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

/** Calcule le nombre de mois couvert par une periode (debut/fin explicites ou texte libre). */
fun computeMonths(debut: LocalDate?, fin: LocalDate?, periodeText: String?): Long? {
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

/** Lit un montant depuis `Document.donneesExtraites` sur un jeu de cles possibles. */
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

/** Lit une chaine depuis `Document.donneesExtraites` sur un jeu de cles possibles. */
fun docStr(doc: Document?, vararg keys: String): String? {
    val data = doc?.donneesExtraites ?: return null
    for (k in keys) {
        val v = data[k] ?: data.entries.find { it.key.equals(k, ignoreCase = true) }?.value
        if (v != null && v.toString().isNotBlank()) return v.toString()
    }
    return null
}

/** Interprete un booleen exprime en langage naturel. */
fun parseBooleanish(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> v.lowercase().trim().let { s -> when { s in TRUTHY -> true; s in FALSY -> false; else -> null } }
    is Number -> v.toInt() != 0
    else -> null
}

/** Convertit un `Any?` (Number/String) en BigDecimal tolerant aux separateurs FR. */
fun toBd(v: Any?): BigDecimal? = when (v) {
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

/** Normalise un libelle pour comparaison (casse, accents, ponctuation). */
fun normalizeLabel(s: String?): String {
    if (s.isNullOrBlank()) return ""
    val nfd = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
    return nfd
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Score Jaccard sur tokens normalises (0.0 = disjoint, 1.0 = identique). */
fun labelSimilarity(a: String?, b: String?): Double {
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

/** Construit un [ValidationEvidence] sans se soucier des champs optionnels du document. */
fun evidence(role: String, champ: String, libelle: String?, doc: Document?, valeur: Any?): ValidationEvidence =
    ValidationEvidence(
        role = role, champ = champ, libelle = libelle,
        documentId = doc?.id?.toString(),
        documentType = doc?.typeDocument?.name,
        valeur = valeur?.toString()?.takeIf { it.isNotBlank() }
    )

/** Construit un resultat CONFORME/NON_CONFORME pour la comparaison de deux montants. */
fun checkMontant(
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

/**
 * Variante de [checkMontant] qui detecte les factures "1/N du BC" (2, 3, 4, 6, 12)
 * et les degrade en AVERTISSEMENT plutot qu'en NON_CONFORME (couverture partielle).
 */
fun checkMontantWithFraction(
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

/**
 * Fusionne le verdict systeme avec la validation manuelle d'un point de checklist.
 * Si l'operateur declare "non conforme", on degrade le verdict meme si le systeme l'avait OK.
 */
fun mergeStatut(systemStatut: StatutCheck, checklistValide: Boolean?): StatutCheck {
    if (checklistValide == null) return systemStatut
    val ckStatut = if (checklistValide) StatutCheck.CONFORME else StatutCheck.NON_CONFORME
    return if (systemStatut == StatutCheck.NON_CONFORME || ckStatut == StatutCheck.NON_CONFORME) StatutCheck.NON_CONFORME
    else if (systemStatut == StatutCheck.AVERTISSEMENT || ckStatut == StatutCheck.AVERTISSEMENT) StatutCheck.AVERTISSEMENT
    else StatutCheck.CONFORME
}

/** Parse la configuration CSV `app.required-documents` en liste de [TypeDocument]. */
fun parseCustomTypes(csv: String?): List<TypeDocument>? {
    if (csv == null) return null
    return csv.split(",").mapNotNull { raw ->
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) null else runCatching { TypeDocument.valueOf(trimmed) }.getOrNull()
    }
}
