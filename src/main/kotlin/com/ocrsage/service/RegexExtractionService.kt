package com.ocrsage.service

import com.ocrsage.dto.ExtractedInvoiceData
import com.ocrsage.dto.ExtractedLineItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Deterministic extraction of Moroccan invoice data using regex patterns.
 * No AI dependency — works offline, fast, and predictable.
 *
 * Handles:
 * - Moroccan fiscal IDs: ICE (15 digits), IF, RC, Patente, CNSS
 * - Moroccan TVA rates: 0%, 7%, 10%, 14%, 20%
 * - French and Arabic number/date formats
 * - Common Moroccan invoice layouts
 */
@Service
class RegexExtractionService {

    private val log = LoggerFactory.getLogger(javaClass)

    // --- ICE: always 15 digits ---
    private val icePattern = Regex(
        """(?:I\.?C\.?E\.?\s*[:.]?\s*)(\d{15})""",
        RegexOption.IGNORE_CASE
    )
    // Also match standalone 15-digit number near "ICE" keyword
    private val iceStandalonePattern = Regex("""(?:ICE|I\.C\.E)\D{0,5}(\d{15})""", RegexOption.IGNORE_CASE)

    // --- IF (Identifiant Fiscal) ---
    private val ifPattern = Regex(
        """(?:I\.?F\.?\s*[:.]?\s*|Identifiant\s+Fiscal\s*[:.]?\s*)(\d{6,10})""",
        RegexOption.IGNORE_CASE
    )

    // --- RC (Registre de Commerce) ---
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

    // --- Invoice number ---
    private val invoiceNumberPatterns = listOf(
        Regex("""(?:Facture|Fact\.?|Invoice|N°\s*Facture|N°\s*Fact)\s*(?:N°|n°|#|:)?\s*[:.]?\s*([A-Za-z0-9\-/]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:N°|Numéro)\s*[:.]?\s*([A-Za-z0-9\-/]+\d+)""", RegexOption.IGNORE_CASE)
    )

    // --- Dates (DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY, YYYY-MM-DD) ---
    private val datePatterns = listOf(
        Regex("""(?:Date\s*(?:de\s+)?(?:facture|facturation|émission|Facture)\s*[:.]?\s*)(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})""", RegexOption.IGNORE_CASE),
        Regex("""(?:Date\s*[:.]?\s*)(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})""", RegexOption.IGNORE_CASE),
        Regex("""(?:Le\s+)(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{4})""", RegexOption.IGNORE_CASE)
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
    private val dueDatePattern = Regex(
        """(?:[ÉE]ch[ée]ance|Date\s+d'[ée]ch[ée]ance|Date\s+limite|Due\s+date)\s*[:.]?\s*(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})""",
        RegexOption.IGNORE_CASE
    )

    // --- Amounts ---
    // Matches: 1 234,56  or  1234.56  or  1,234.56  or  1.234,56
    private val amountRegex = """[\d\s]+[.,]\d{2}"""

    private val amountHtPatterns = listOf(
        Regex("""(?:Total\s+H\.?T\.?|Montant\s+H\.?T\.?|Base\s+H\.?T\.?|Sous[- ]?total\s+H\.?T\.?)\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""H\.?T\.?\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE)
    )

    private val amountTvaPatterns = listOf(
        Regex("""(?:Total\s+T\.?V\.?A\.?|Montant\s+T\.?V\.?A\.?|T\.?V\.?A\.?\s+\d+\s*%?)\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""T\.?V\.?A\.?\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE)
    )

    private val amountTtcPatterns = listOf(
        Regex("""(?:Total\s+T\.?T\.?C\.?|Montant\s+T\.?T\.?C\.?|Net\s+[àa]\s+payer|Total\s+g[ée]n[ée]ral)\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""T\.?T\.?C\.?\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""Net\s+[àa]\s+payer\s*[:.]?\s*($amountRegex)""", RegexOption.IGNORE_CASE)
    )

    // --- TVA rate ---
    private val tvaRatePattern = Regex(
        """T\.?V\.?A\.?\s*(?:au\s+taux\s+de\s+|[:.]?\s*)?(\d{1,2})\s*%""",
        RegexOption.IGNORE_CASE
    )

    // --- Discount ---
    private val discountPatterns = listOf(
        Regex("""(?:Remise|Rabais|Escompte)\s*(?:\(\s*(\d{1,3}(?:[.,]\d+)?)\s*%\s*\))?\s*[:.]?\s*-?\s*($amountRegex)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Remise|Rabais)\s*[:.]?\s*(\d{1,3}(?:[.,]\d+)?)\s*%""", RegexOption.IGNORE_CASE)
    )

    // --- Payment method ---
    private val paymentPatterns = listOf(
        Regex("""(?:Mode\s+de\s+(?:paiement|règlement)|Règlement|Paiement)\s*[:.]?\s*(Virement|Chèque|Ch[eè]que|Espèces|Traite|Effet|LCN|Carte|Prélèvement|Compensation)""", RegexOption.IGNORE_CASE)
    )

    // --- RIB (24 digits for Morocco) ---
    private val ribPattern = Regex(
        """(?:R\.?I\.?B\.?\s*[:.]?\s*)(\d[\d\s]{22,30}\d)""",
        RegexOption.IGNORE_CASE
    )

    // --- Bank name ---
    private val bankPatterns = listOf(
        Regex("""(?:Banque|Bank)\s*[:.]?\s*(.+?)(?:\n|R\.?I\.?B|$)""", RegexOption.IGNORE_CASE),
        Regex("""(Attijariwafa|BMCE|BMCI|CIH|Banque\s+Populaire|Société\s+Générale|Crédit\s+du\s+Maroc|CDM|Bank\s+Al[\s-]Maghrib|BAM|SGMB|CFG|Crédit\s+Agricole|Al\s+Barid)""", RegexOption.IGNORE_CASE)
    )

    // --- Currency ---
    private val currencyPattern = Regex(
        """(MAD|DH|DHs?|Dirhams?|EUR|€|USD|\$)""",
        RegexOption.IGNORE_CASE
    )

    fun extract(rawText: String): ExtractedInvoiceData {
        log.info("Starting regex extraction on {} chars", rawText.length)

        val ice = findFirst(rawText, icePattern) ?: findFirst(rawText, iceStandalonePattern)
        val invoiceIf = findFirst(rawText, ifPattern)
        val rc = findFirst(rawText, rcPattern)
        val patente = findFirst(rawText, patentePattern)
        val cnss = findFirst(rawText, cnssPattern)

        val invoiceNumber = findFirstFromList(rawText, invoiceNumberPatterns)
        val invoiceDate = extractDate(rawText, datePatterns)
        val dueDate = extractSingleDate(rawText, dueDatePattern)

        val amountHt = extractAmount(rawText, amountHtPatterns)
        val amountTva = extractAmount(rawText, amountTvaPatterns)
        val amountTtc = extractAmount(rawText, amountTtcPatterns)
        val tvaRate = tvaRatePattern.find(rawText)?.groupValues?.get(1)?.toBigDecimalOrNull()

        val discount = extractDiscount(rawText)

        val paymentMethod = findFirstFromList(rawText, paymentPatterns)
        val bankName = findFirstFromList(rawText, bankPatterns)?.trim()
        val rib = ribPattern.find(rawText)?.groupValues?.get(1)?.replace("\\s".toRegex(), "")

        val currency = detectCurrency(rawText)

        // Try to extract supplier name from first lines
        val supplierName = extractSupplierName(rawText)
        val supplierAddress = extractAddress(rawText)
        val supplierCity = extractCity(rawText)

        // Extract client info
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
            ice, invoiceIf, rc, invoiceNumber, invoiceDate, amountHt, amountTtc, supplierName
        ).size
        log.info("Regex extraction done: {} fields extracted", fieldsFound)

        return result
    }

    private fun findFirst(text: String, pattern: Regex): String? =
        pattern.find(text)?.groupValues?.get(1)?.trim()

    private fun findFirstFromList(text: String, patterns: List<Regex>): String? =
        patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim() }

    private fun extractDate(text: String, patterns: List<Regex>): LocalDate? {
        for (pattern in patterns) {
            val match = pattern.find(text)?.groupValues?.get(1) ?: continue
            return parseDate(match)
        }
        return null
    }

    private fun extractSingleDate(text: String, pattern: Regex): LocalDate? {
        val match = pattern.find(text)?.groupValues?.get(1) ?: return null
        return parseDate(match)
    }

    private fun parseDate(dateStr: String): LocalDate? {
        for (fmt in dateFormatters) {
            try {
                val date = LocalDate.parse(dateStr, fmt)
                // Handle 2-digit years
                return if (date.year < 100) date.plusYears(2000) else date
            } catch (_: DateTimeParseException) {
                continue
            }
        }
        return null
    }

    private fun parseAmount(amountStr: String): BigDecimal? {
        // Normalize: "1 234,56" → "1234.56", "1.234,56" → "1234.56"
        val cleaned = amountStr.trim()
        return try {
            val hasCommaDecimal = cleaned.matches(Regex(""".*,\d{2}$"""))
            val normalized = if (hasCommaDecimal) {
                cleaned.replace("\\s".toRegex(), "").replace(".", "").replace(",", ".")
            } else {
                cleaned.replace("\\s".toRegex(), "").replace(",", "")
            }
            BigDecimal(normalized)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun extractAmount(text: String, patterns: List<Regex>): BigDecimal? {
        for (pattern in patterns) {
            val match = pattern.find(text)?.groupValues?.get(1) ?: continue
            val amount = parseAmount(match)
            if (amount != null && amount > BigDecimal.ZERO) return amount
        }
        return null
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
        val match = currencyPattern.find(text)?.groupValues?.get(1)?.uppercase() ?: return "MAD"
        return when {
            match in listOf("DH", "DHS", "DIRHAM", "DIRHAMS") -> "MAD"
            match in listOf("€", "EUR") -> "EUR"
            match in listOf("$", "USD") -> "USD"
            else -> match
        }
    }

    private fun extractSupplierName(text: String): String? {
        // Usually the company name is in the first few lines, before any fiscal IDs
        val lines = text.lines().take(15)
        // Skip empty lines and very short lines at the top
        val candidateLines = lines
            .map { it.trim() }
            .filter { it.length > 3 && !it.matches(Regex("^\\d+$")) }
            .filter { !it.contains(Regex("(?:facture|date|ice|i\\.f|r\\.c|patente|tél|fax|email|adresse|n°)", RegexOption.IGNORE_CASE)) }

        return candidateLines.firstOrNull()
    }

    private fun extractAddress(text: String): String? {
        val pattern = Regex(
            """(?:Adresse|Adr\.?)\s*[:.]?\s*(.+?)(?:\n|Tél|Tel|Fax|Email|ICE|$)""",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(text)?.groupValues?.get(1)?.trim()
    }

    private fun extractCity(text: String): String? {
        // Common Moroccan cities
        val cities = listOf(
            "Casablanca", "Rabat", "Marrakech", "Fès", "Fez", "Tanger", "Tangier",
            "Agadir", "Meknès", "Meknes", "Oujda", "Kénitra", "Kenitra", "Tétouan",
            "Tetouan", "Safi", "El Jadida", "Mohammedia", "Nador", "Béni Mellal",
            "Beni Mellal", "Khémisset", "Taza", "Settat", "Berrechid", "Laâyoune",
            "Laayoune", "Khouribga", "Salé", "Sale", "Temara", "Témara"
        )
        val pattern = Regex(cities.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        return pattern.find(text)?.value
    }

    private fun extractClientInfo(text: String): Pair<String?, String?> {
        // Look for "Client:" or "Destinataire:" section
        val clientNamePattern = Regex(
            """(?:Client|Destinataire|Livré\s+à|Adressé\s+à|Doit\s*:)\s*[:.]?\s*(.+?)(?:\n|ICE|$)""",
            RegexOption.IGNORE_CASE
        )
        val name = clientNamePattern.find(text)?.groupValues?.get(1)?.trim()

        // Look for second ICE (client ICE) — usually after "Client" section
        val allIces = icePattern.findAll(text).map { it.groupValues[1] }.toList() +
                iceStandalonePattern.findAll(text).map { it.groupValues[1] }.toList()
        val clientIce = if (allIces.size >= 2) allIces[1] else null

        return Pair(name, clientIce)
    }
}
