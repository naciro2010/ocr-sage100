package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.Facture
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

/** Normalise un identifiant B2B (IF/RC/CNSS) : supprime espaces + zeros en tete. */
fun normalizeId(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return value.replace(WHITESPACE_RE, "").trimStart('0').ifEmpty { "0" }
}

/**
 * Normalise un ICE en preservant les zeros initiaux significatifs.
 * Selon le decret 2-11-13 et l'arrete OMPIC, l'ICE est strictement de
 * 15 chiffres : `001509176000008` n'est PAS equivalent a `1509176000008`.
 * `normalizeId` (qui retire les zeros de tete) ne doit JAMAIS etre
 * utilisee pour comparer deux ICE — c'etait un bug qui faisait passer
 * pour identiques deux ICE structurellement differents.
 */
fun normalizeIce(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return value.replace(WHITESPACE_RE, "").ifEmpty { null }
}

/** ICE valide = exactement 15 chiffres apres normalisation espaces/ponctuation. */
private val ICE_FORMAT_RE = Regex("^[0-9]{15}$")
fun isIceFormatValid(ice: String?): Boolean {
    val normalized = normalizeIce(ice) ?: return false
    return ICE_FORMAT_RE.matches(normalized)
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

/**
 * Detecte si deux noms de personnes designent vraisemblablement la meme
 * personne, en tolerant les variations courantes :
 *   - "Mohamed Alami" vs "M. Alami" (initiale + nom de famille)
 *   - "Mohamed Alami, DAF" vs "M. ALAMI - Directeur" (titre / fonction)
 *   - "ALAMI Mohamed" vs "Mohamed Alami" (ordre inverse)
 *
 * Heuristique :
 *   1. Normalisation (casse / accents / ponctuation, cf. normalizeLabel).
 *   2. Match du nom de famille (dernier token significatif >= 3 lettres).
 *   3. Si nom de famille match, verifier compatibilite des prenoms /
 *      initiales (chaque prenom de la version courte doit avoir un
 *      equivalent dans la version longue : meme mot OU initiale).
 *   4. Sinon, tomber sur Jaccard classique (seuil 0.6) pour les noms tres
 *      differents qui partageraient encore beaucoup de tokens.
 *
 * Conservatif : prefere flagger un faux positif (alerter sur deux personnes
 * vraiment distinctes) plutot qu'un faux negatif (laisser passer une meme
 * personne sur les 2 roles ordonnateur/comptable, vice de procedure majeur).
 */
fun personNamesLikelySame(a: String?, b: String?): Boolean {
    val ta = normalizeLabel(a).split(" ").filter { it.isNotBlank() }
    val tb = normalizeLabel(b).split(" ").filter { it.isNotBlank() }
    if (ta.isEmpty() || tb.isEmpty()) return false
    // Cas trivial : meme suite de tokens (ordre identique ou non).
    if (ta.toSet() == tb.toSet()) return true
    // Heuristique nom de famille : on tente DEUX positions (dernier token
    // ET premier token >= 3 lettres) parce que les noms marocains sont
    // ecrits soit "Mohamed Alami" soit "ALAMI Mohamed".
    val candidatesA = setOfNotNull(
        ta.last().takeIf { it.length >= 3 },
        ta.first().takeIf { it.length >= 3 }
    )
    val candidatesB = setOfNotNull(
        tb.last().takeIf { it.length >= 3 },
        tb.first().takeIf { it.length >= 3 }
    )
    val familyMatches = candidatesA.intersect(candidatesB)
    if (familyMatches.isNotEmpty()) {
        val family = familyMatches.first()
        // Verifier compatibilite des prenoms / initiales (pas deux personnes
        // distinctes qui partageraient le meme nom de famille).
        val (short, long) = if (ta.size <= tb.size) ta to tb else tb to ta
        val firstShort = short.filter { it != family }
        val firstLong = long.filter { it != family }
        if (firstShort.isEmpty()) return true
        if (firstLong.isEmpty()) return true
        return firstShort.all { s ->
            firstLong.any { l ->
                s == l
                    || (s.length == 1 && l.startsWith(s))
                    || (l.length == 1 && s.startsWith(l))
            }
        }
    }
    // Fallback Jaccard sur tokens significatifs (seuil 0.6).
    val sa = ta.filter { it.length > 1 }.toSet()
    val sb = tb.filter { it.length > 1 }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return false
    val ratio = sa.intersect(sb).size.toDouble() / sa.union(sb).size.toDouble()
    return ratio >= 0.6
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

private val AVOIR_NUMERO_RE = Regex("\\b(avoir|annul|annulation|rectif|rectificatif|av-|cn-|credit\\s*note|note\\s*de\\s*credit)\\b", RegexOption.IGNORE_CASE)

/**
 * Detecte les factures qui sont en realite des avoirs / annulations /
 * compensations : montant TTC negatif, libelle de numero contenant
 * AVOIR / ANNUL / RECTIF / AV- / CN-, ou type de document explicitement
 * marque comme tel dans `donneesExtraites`.
 *
 * Cette detection est utilisee par R21 pour ne pas confondre une
 * compensation legitime (facture + avoir du meme montant) avec un
 * doublon de facturation.
 */
fun isFactureAvoir(f: Facture): Boolean {
    val ttc = f.montantTtc
    if (ttc != null && ttc.signum() < 0) return true
    val numero = f.numeroFacture
    if (!numero.isNullOrBlank() && AVOIR_NUMERO_RE.containsMatchIn(numero)) return true
    val typeFromJson = f.document.donneesExtraites?.get("typeFacture")?.toString()
    if (!typeFromJson.isNullOrBlank() && AVOIR_NUMERO_RE.containsMatchIn(typeFromJson)) return true
    return false
}

/** Parse la configuration CSV `app.required-documents` en liste de [TypeDocument]. */
fun parseCustomTypes(csv: String?): List<TypeDocument>? {
    if (csv == null) return null
    return csv.split(",").mapNotNull { raw ->
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) null else runCatching { TypeDocument.valueOf(trimmed) }.getOrNull()
    }
}
