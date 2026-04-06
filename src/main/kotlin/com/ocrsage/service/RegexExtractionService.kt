package com.ocrsage.service

import com.ocrsage.dto.ExtractedInvoiceData
import com.ocrsage.dto.ExtractedLineItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Service
class RegexExtractionService {

    private val log = LoggerFactory.getLogger(javaClass)

    // --- ICE: always 15 digits ---
    private val icePatterns = listOf(
        Regex("""(?:I\.?C\.?E\.?\s*[:.]?\s*)(\d{15})""", RegexOption.IGNORE_CASE),
        Regex("""(?:ICE|I\.C\.E)\D{0,5}(\d{15})""", RegexOption.IGNORE_CASE),
        // ICE sans label mais 15 chiffres isoles sur une ligne
        Regex("""^\s*(\d{15})\s*$""", RegexOption.MULTILINE)
    )

    // --- IF (Identifiant Fiscal) ---
    private val ifPatterns = listOf(
        Regex("""(?:I\.?F\.?\s*[:.]?\s*|Identifiant\s+Fiscal\s*[:.]?\s*)(\d{6,10})""", RegexOption.IGNORE_CASE),
        Regex("""(?:Id\s*Fiscal|Id\.?\s*F\.?)\s*[:.]?\s*(\d{6,10})""", RegexOption.IGNORE_CASE)
    )

    // --- RC ---
    private val rcPattern = Regex(
        """(?:R\.?C\.?\s*[:.]?\s*|Registre\s+(?:de\s+)?Commerce\s*[:.]?\s*)([A-Za-z0-9\-/]+)""",
        RegexOption.IGNORE_CASE
    )

    // --- Patente ---
    private val patentePattern = Regex(
        """(?:Patente\s*[:.]?\s*)(\d{6,12})""",
        RegexOption.IGNORE_CASE
    )

    // --- CNSS ---
    private val cnssPattern = Regex(
        """(?:C\.?N\.?S\.?S\.?\s*[:.]?\s*)(\d{6,12})""",
        RegexOption.IGNORE_CASE
    )

    // --- Invoice number (more patterns) ---
    private val invoiceNumberPatterns = listOf(
        Regex("""(?:Facture|Fact\.?|Invoice|N°\s*Facture|N°\s*Fact|Fac)[ \t]*(?:N°|n°|#|:)[ \t]*[:.]?[ \t]*([A-Za-z0-9\-/]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Facture|Fact\.?|Invoice)[ \t]+([A-Z0-9][\w\-/]*\d[\w\-/]*)""", RegexOption.IGNORE_CASE),
        Regex("""(?:N°|Numéro|Numero|Num)\s*[:.]?\s*([A-Za-z0-9\-/]+\d+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Ref|Réf|Reference)\s*[:.]?\s*([A-Za-z0-9\-/]+\d+)""", RegexOption.IGNORE_CASE),
        // Pattern : "FACT-2024-001" ou "FA24/001" standalone
        Regex("""\b(F(?:ACT?|A)\s*[-/]?\s*\d{2,4}\s*[-/]\s*\d{2,6})\b""", RegexOption.IGNORE_CASE)
    )

    // --- Dates ---
    private val datePatterns = listOf(
        Regex("""(?:Date\s*(?:de\s+)?(?:facture|facturation|[ée]mission|Facture)\s*[:.]?\s*)(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})""", RegexOption.IGNORE_CASE),
        Regex("""(?:Date\s*[:.]?\s*)(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})""", RegexOption.IGNORE_CASE),
        Regex("""(?:Le\s+|En date du\s+|Du\s+)(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{4})""", RegexOption.IGNORE_CASE),
        // Format ISO: 2024-01-15
        Regex("""(?:Date\s*[:.]?\s*)(\d{4}-\d{2}-\d{2})""", RegexOption.IGNORE_CASE)
    )

    private val dateFormatters = listOf(
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd/MM/yy"),
        DateTimeFormatter.ofPattern("dd-MM-yy")
    )

    // --- Due date ---
    private val dueDatePatterns = listOf(
        Regex("""(?:[ÉEe]ch[ée]ance|Date\s+d'[ée]ch[ée]ance|Date\s+limite|Due\s+date|Payable\s+(?:avant|le))\s*[:.]?\s*(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})""", RegexOption.IGNORE_CASE)
    )

    // --- Amounts (plus flexible) ---
    // Matches: "1 234,56" "1234.56" "1,234.56" "1.234,56" "12345" "1234,5"
    private val amountRegex = """[\d][\d\s]*(?:[.,]\d{1,2})?"""

    private val amountHtPatterns = listOf(
        Regex("""(?:Total\s+H\.?T\.?|Montant\s+H\.?T\.?|Base\s+H\.?T\.?|Sous[- ]?total\s+H\.?T\.?|Total\s+hors\s+tax)\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""H\.?T\.?\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Hors\s+Tax(?:e|es)?)\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE)
    )

    private val amountTvaPatterns = listOf(
        Regex("""(?:Total\s+T\.?V\.?A\.?|Montant\s+T\.?V\.?A\.?)\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""T\.?V\.?A\.?\s+\d+\s*%?\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Taxe|Tax)\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE)
    )

    private val amountTtcPatterns = listOf(
        Regex("""(?:Total\s+T\.?T\.?C\.?|Montant\s+T\.?T\.?C\.?|Net\s+[àa]\s+payer|Total\s+g[ée]n[ée]ral|Total\s+facture)\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""T\.?T\.?C\.?\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""Net\s+[àa]\s+payer\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Toutes\s+Taxes\s+Comprises?)\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE)
    )

    // --- TVA rate ---
    private val tvaRatePatterns = listOf(
        Regex("""T\.?V\.?A\.?\s*(?:au\s+taux\s+de\s+|[:.]?\s*)?(\d{1,2})\s*%""", RegexOption.IGNORE_CASE),
        Regex("""(\d{1,2})\s*%\s*T\.?V\.?A""", RegexOption.IGNORE_CASE),
        Regex("""Taux\s*[:.]?\s*(\d{1,2})\s*%""", RegexOption.IGNORE_CASE)
    )

    // --- Discount ---
    private val discountPatterns = listOf(
        Regex("""(?:Remise|Rabais|Escompte)\s*(?:\(\s*(\d{1,3}(?:[.,]\d+)?)\s*%\s*\))?\s*[:.]?\s*-?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Remise|Rabais)\s*[:.]?\s*(\d{1,3}(?:[.,]\d+)?)\s*%""", RegexOption.IGNORE_CASE)
    )

    // --- Payment method ---
    private val paymentPatterns = listOf(
        Regex("""(?:Mode\s+de\s+(?:paiement|r[èe]glement)|R[èe]glement|Paiement|Pay[ée]\s+par)\s*[:.]?\s*(Virement|Ch[èe]que|Espèces|Especes|Traite|Effet|LCN|Carte|Pr[ée]l[èe]vement|Compensation)""", RegexOption.IGNORE_CASE)
    )

    // --- RIB (24 digits for Morocco) ---
    private val ribPattern = Regex(
        """(?:R\.?I\.?B\.?\s*[:.]?\s*)(\d[\d\s]{22,30}\d)""",
        RegexOption.IGNORE_CASE
    )
    // RIB sans label : 24 chiffres consecutifs
    private val ribStandalonePattern = Regex("""(?<!\d)(\d{24})(?!\d)""")

    // --- Bank name ---
    private val bankPatterns = listOf(
        Regex("""(?:Banque|Bank)\s*[:.]?\s*(.+?)(?:\n|R\.?I\.?B|$)""", RegexOption.IGNORE_CASE),
        Regex("""(Attijariwafa|BMCE|BMCI|CIH|Banque\s+Populaire|Soci[ée]t[ée]\s+G[ée]n[ée]rale|Cr[ée]dit\s+du\s+Maroc|CDM|Bank\s+Al[\s-]Maghrib|BAM|SGMB|CFG|Cr[ée]dit\s+Agricole|Al\s+Barid|Umnia|Bank\s+Assafa|BTI\s+Bank|Al\s+Akhdar)""", RegexOption.IGNORE_CASE)
    )

    // --- Currency ---
    private val currencyPattern = Regex(
        """\b(MAD|DH|DHs|Dirhams?|EUR|USD)\b|([€$])""",
        RegexOption.IGNORE_CASE
    )

    // --- Moroccan cities ---
    // Villes principales d'abord (priorite), puis secondaires
    private val moroccanCities = listOf(
        "Casablanca", "Rabat", "Marrakech", "Fès", "Fez", "Tanger", "Tangier",
        "Agadir", "Meknès", "Meknes", "Oujda", "Kénitra", "Kenitra", "Tétouan",
        "Tetouan", "Safi", "El Jadida", "Mohammedia", "Nador", "Béni Mellal",
        "Beni Mellal", "Khémisset", "Taza", "Settat", "Berrechid", "Laâyoune",
        "Laayoune", "Khouribga", "Salé", "Sale", "Temara", "Témara"
    )

    fun extract(rawText: String): ExtractedInvoiceData {
        log.info("Starting regex extraction on {} chars", rawText.length)

        val ice = findFirstFromPatterns(rawText, icePatterns)
        val invoiceIf = findFirstFromPatterns(rawText, ifPatterns)
        val rc = findFirst(rawText, rcPattern)
        val patente = findFirst(rawText, patentePattern)
        val cnss = findFirst(rawText, cnssPattern)

        val invoiceNumber = findFirstFromList(rawText, invoiceNumberPatterns)
        val invoiceDate = extractDate(rawText, datePatterns)
        val dueDate = extractDateFromList(rawText, dueDatePatterns)

        var amountHt = extractAmount(rawText, amountHtPatterns)
        var amountTva = extractAmount(rawText, amountTvaPatterns)
        var amountTtc = extractAmount(rawText, amountTtcPatterns)
        val tvaRate = extractTvaRate(rawText)

        // --- CALCUL DES CHAMPS MANQUANTS ---
        val computed = computeMissingAmounts(amountHt, amountTva, amountTtc, tvaRate)
        amountHt = computed.ht
        amountTva = computed.tva
        amountTtc = computed.ttc

        val discount = extractDiscount(rawText)
        val paymentMethod = findFirstFromList(rawText, paymentPatterns)
        val bankName = findFirstFromList(rawText, bankPatterns)?.trim()

        // RIB : d'abord avec label, sinon standalone 24 chiffres (si pres d'un contexte bancaire)
        var rib = ribPattern.find(rawText)?.groupValues?.get(1)?.replace("\\s".toRegex(), "")
        if (rib == null && bankName != null) {
            // Chercher 24 chiffres pres du contexte bancaire
            rib = ribStandalonePattern.find(rawText)?.groupValues?.get(1)
        }

        val currency = detectCurrency(rawText)
        val supplierName = extractSupplierName(rawText, ice)
        val supplierAddress = extractAddress(rawText)
        val supplierCity = extractCity(rawText)
        val clientInfo = extractClientInfo(rawText)

        val result = ExtractedInvoiceData(
            supplierName = supplierName,
            supplierIce = ice,
            supplierIf = invoiceIf,
            supplierRc = rc,
            supplierPatente = patente,
            supplierCnss = cnss,
            supplierAddress = supplierAddress,
            supplierCity = supplierCity,
            clientName = clientInfo.first,
            clientIce = clientInfo.second,
            invoiceNumber = invoiceNumber,
            invoiceDate = invoiceDate,
            amountHt = amountHt,
            tvaRate = tvaRate,
            amountTva = amountTva,
            amountTtc = amountTtc,
            discountAmount = discount.first,
            discountPercent = discount.second,
            currency = currency,
            paymentMethod = paymentMethod,
            paymentDueDate = dueDate,
            bankName = bankName,
            bankRib = rib
        )

        val fieldsFound = listOfNotNull(
            ice, invoiceIf, rc, invoiceNumber, invoiceDate,
            amountHt, amountTva, amountTtc, supplierName, tvaRate
        ).size
        log.info("Regex extraction done: {} fields extracted", fieldsFound)

        return result
    }

    // --- Calcul intelligent des montants manquants ---

    data class ComputedAmounts(val ht: BigDecimal?, val tva: BigDecimal?, val ttc: BigDecimal?)

    private fun computeMissingAmounts(
        ht: BigDecimal?, tva: BigDecimal?, ttc: BigDecimal?, tvaRate: BigDecimal?
    ): ComputedAmounts {
        var h = ht
        var t = tva
        var c = ttc

        // Cas 1: HT + TVA connus -> calculer TTC
        if (h != null && t != null && c == null) {
            c = h.add(t)
            log.debug("Computed TTC = HT + TVA = {} + {} = {}", h, t, c)
        }

        // Cas 2: HT + taux TVA connus -> calculer TVA et TTC
        if (h != null && tvaRate != null && t == null) {
            t = h.multiply(tvaRate).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            if (c == null) c = h.add(t)
            log.debug("Computed TVA = {}% of {} = {}, TTC = {}", tvaRate, h, t, c)
        }

        // Cas 3: TTC + TVA connus -> calculer HT
        if (c != null && t != null && h == null) {
            h = c.subtract(t)
            log.debug("Computed HT = TTC - TVA = {} - {} = {}", c, t, h)
        }

        // Cas 4: TTC + taux TVA connus -> calculer HT et TVA
        if (c != null && tvaRate != null && h == null && tvaRate > BigDecimal.ZERO) {
            h = c.divide(BigDecimal.ONE.add(tvaRate.divide(BigDecimal(100))), 2, RoundingMode.HALF_UP)
            t = c.subtract(h)
            log.debug("Computed HT = TTC / (1+rate) = {}, TVA = {}", h, t)
        }

        return ComputedAmounts(h, t, c)
    }

    // --- Helpers ---

    private fun findFirst(text: String, pattern: Regex): String? =
        pattern.find(text)?.groupValues?.get(1)?.trim()

    private fun findFirstFromPatterns(text: String, patterns: List<Regex>): String? =
        patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim() }

    private fun findFirstFromList(text: String, patterns: List<Regex>): String? =
        patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim() }

    private fun extractDate(text: String, patterns: List<Regex>): LocalDate? {
        for (pattern in patterns) {
            val match = pattern.find(text)?.groupValues?.get(1) ?: continue
            return parseDate(match)
        }
        return null
    }

    private fun extractDateFromList(text: String, patterns: List<Regex>): LocalDate? {
        return extractDate(text, patterns)
    }

    private fun parseDate(dateStr: String): LocalDate? {
        for (fmt in dateFormatters) {
            try {
                val date = LocalDate.parse(dateStr, fmt)
                return if (date.year < 100) date.plusYears(2000) else date
            } catch (_: DateTimeParseException) {
                continue
            }
        }
        return null
    }

    private fun parseAmount(amountStr: String): BigDecimal? {
        val cleaned = amountStr.trim()
        if (cleaned.isEmpty()) return null
        return try {
            val hasCommaDecimal = cleaned.matches(Regex(""".*,\d{1,2}$"""))
            val normalized = if (hasCommaDecimal) {
                cleaned.replace("\\s".toRegex(), "").replace(".", "").replace(",", ".")
            } else {
                cleaned.replace("\\s".toRegex(), "").replace(",", "")
            }
            val value = BigDecimal(normalized)
            if (value <= BigDecimal.ZERO) null else value
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun extractAmount(text: String, patterns: List<Regex>): BigDecimal? {
        for (pattern in patterns) {
            val match = pattern.find(text)?.groupValues?.get(1) ?: continue
            val amount = parseAmount(match)
            if (amount != null) return amount
        }
        return null
    }

    private fun extractTvaRate(text: String): BigDecimal? {
        for (pattern in tvaRatePatterns) {
            val match = pattern.find(text)?.groupValues?.get(1) ?: continue
            val rate = match.toBigDecimalOrNull() ?: continue
            // Valider que c'est un taux TVA marocain legal
            if (rate in listOf(BigDecimal("0"), BigDecimal("7"), BigDecimal("10"), BigDecimal("14"), BigDecimal("20"))) {
                return rate
            }
        }
        // Fallback : chercher le taux le plus courant mentionne
        val allRates = Regex("""(\d{1,2})\s*%""").findAll(text)
            .map { it.groupValues[1].toIntOrNull() }
            .filterNotNull()
            .filter { it in listOf(0, 7, 10, 14, 20) }
            .toList()
        return allRates.maxByOrNull { rate -> allRates.count { it == rate } }?.toBigDecimal()
    }

    private fun extractDiscount(text: String): Pair<BigDecimal?, BigDecimal?> {
        for (pattern in discountPatterns) {
            val match = pattern.find(text) ?: continue
            val groups = match.groupValues
            return when (groups.size) {
                3 -> {
                    val percent = groups[1].replace(",", ".").toBigDecimalOrNull()
                    val amount = parseAmount(groups[2])
                    Pair(amount, percent)
                }
                2 -> {
                    val percent = groups[1].replace(",", ".").toBigDecimalOrNull()
                    Pair(null, percent)
                }
                else -> Pair(null, null)
            }
        }
        return Pair(null, null)
    }

    private fun detectCurrency(text: String): String {
        val result = currencyPattern.find(text) ?: return "MAD"
        val match = (result.groupValues[1].ifEmpty { result.groupValues[2] }).uppercase()
        if (match.isEmpty()) return "MAD"
        return when {
            match in listOf("DH", "DHS", "DIRHAM", "DIRHAMS") -> "MAD"
            match in listOf("€", "EUR") -> "EUR"
            match in listOf("$", "USD") -> "USD"
            else -> match
        }
    }

    /**
     * Extraction intelligente du nom fournisseur.
     * Strategie : chercher la ligne juste avant ou apres l'ICE/IF,
     * car sur une facture marocaine le nom est toujours pres des identifiants fiscaux.
     * Fallback : premiere ligne significative du document.
     */
    private fun extractSupplierName(text: String, ice: String?): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        // Strategie 1 : ligne juste avant le premier ICE/IF/RC
        val fiscalKeywords = listOf("ICE", "I.C.E", "I.F.", "R.C.", "Patente", "CNSS", "Identifiant Fiscal")
        val fiscalLineIndex = lines.indexOfFirst { line ->
            fiscalKeywords.any { kw -> line.contains(kw, ignoreCase = true) }
        }

        if (fiscalLineIndex > 0) {
            // Remonter pour trouver le nom (1-3 lignes avant les IDs fiscaux)
            for (i in (fiscalLineIndex - 1) downTo maxOf(0, fiscalLineIndex - 4)) {
                val candidate = lines[i]
                if (isLikelyCompanyName(candidate)) {
                    return cleanCompanyName(candidate)
                }
            }
        }

        // Strategie 2 : premiere ligne qui ressemble a un nom d'entreprise
        val skipPatterns = listOf(
            Regex("^\\d+$"),
            Regex("^(facture|date|page|tel|fax|email|www|http)", RegexOption.IGNORE_CASE),
            Regex("^\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}"),
            Regex("^(adresse|adr|bp|boite)", RegexOption.IGNORE_CASE)
        )

        for (line in lines.take(10)) {
            if (line.length < 3 || line.length > 100) continue
            if (skipPatterns.any { it.containsMatchIn(line) }) continue
            if (isLikelyCompanyName(line)) {
                return cleanCompanyName(line)
            }
        }

        return null
    }

    private fun isLikelyCompanyName(line: String): Boolean {
        if (line.length < 3 || line.length > 100) return false
        // Contient des mots typiques de raison sociale
        val companyIndicators = listOf("sarl", "s.a.r.l", "sa", "s.a.", "sas", "eurl",
            "ste", "société", "societe", "entreprise", "ets", "cabinet", "group",
            "international", "maroc", "morocco", "import", "export", "trading",
            "consulting", "services", "industries", "distribution")
        if (companyIndicators.any { line.contains(it, ignoreCase = true) }) return true
        // Au moins 2 mots, commence par majuscule, pas que des chiffres
        val words = line.split(Regex("\\s+"))
        return words.size >= 2 && line[0].isUpperCase() && !line.matches(Regex(".*\\d{6,}.*"))
    }

    private fun cleanCompanyName(name: String): String {
        // Retirer les numeros de telephone, fax, emails en fin de ligne
        return name
            .replace(Regex("\\s*(Tel|Tél|Fax|GSM|Email|Mob).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\d{2}[.\\s]\\d{2}[.\\s]\\d{2}[.\\s]\\d{2}[.\\s]\\d{2}\\s*$"), "")
            .trim()
    }

    private fun extractAddress(text: String): String? {
        val patterns = listOf(
            Regex("""(?:Adresse|Adr\.?|Siege)\s*[:.]?\s*(.+?)(?:\n|Tél|Tel|Fax|Email|ICE|$)""", RegexOption.IGNORE_CASE),
            // Adresse typique marocaine : contient "rue", "bd", "av", "lot", "n°"
            Regex("""((?:\d+\s*,?\s*)?(?:Rue|Boulevard|Bd|Avenue|Av|Lot|Imm|Residence|Quartier|Zone|ZI)\s+.+?)(?:\n|Tél|Tel|$)""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim() }
    }

    private fun extractCity(text: String): String? {
        val pattern = Regex(moroccanCities.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        return pattern.find(text)?.value
    }

    private fun extractClientInfo(text: String): Pair<String?, String?> {
        val clientNamePatterns = listOf(
            Regex("""(?:Client|Destinataire|Livr[ée]\s+[àa]|Adress[ée]\s+[àa]|Doit\s*:|Facturer?\s+[àa])\s*[:.]?\s*(.+?)(?:\n|ICE|$)""", RegexOption.IGNORE_CASE)
        )
        val name = clientNamePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim() }

        // Second ICE = client ICE
        val allIces = icePatterns.flatMap { pattern ->
            pattern.findAll(text).map { it.groupValues[1] }.toList()
        }.distinct()
        val clientIce = if (allIces.size >= 2) allIces[1] else null

        return Pair(name, clientIce)
    }
}
