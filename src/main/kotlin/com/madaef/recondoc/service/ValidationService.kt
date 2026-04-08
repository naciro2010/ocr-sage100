package com.madaef.recondoc.service

import com.madaef.recondoc.entity.Invoice
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class ValidationService {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Perform full validation of a Moroccan invoice.
     */
    fun validateInvoice(invoice: Invoice): ValidationResult {
        log.info("Validating invoice {}", invoice.id)
        val issues = mutableListOf<ValidationIssue>()

        validateIce(invoice.supplierIce, "Fournisseur", issues)
        validateIce(invoice.clientIce, "Client", issues)
        validateIdentifiantFiscal(invoice.supplierIf, issues)
        validateRib(invoice.bankRib, issues)
        validateTvaRate(invoice.tvaRate, issues)
        validateAmounts(invoice, issues)
        validateLineItemsConsistency(invoice, issues)
        validateCurrency(invoice.currency, issues)
        validateRequiredFields(invoice, issues)
        validateLineItemTvaRates(invoice, issues)

        val valid = issues.none { it.severity == ValidationSeverity.ERROR }
        log.info("Invoice {} validation: valid={}, issues={}", invoice.id, valid, issues.size)

        return ValidationResult.fromIssues(
            invoiceId = invoice.id,
            valid = valid,
            issues = issues
        )
    }

    // --- ICE Validation ---

    private fun validateIce(ice: String?, label: String, issues: MutableList<ValidationIssue>) {
        if (ice == null) {
            issues.add(ValidationIssue(
                field = "ICE $label",
                severity = ValidationSeverity.WARNING,
                message = "ICE $label non renseigné"
            ))
            return
        }

        if (!ice.matches(Regex("^\\d{15}$"))) {
            issues.add(ValidationIssue(
                field = "ICE $label",
                severity = ValidationSeverity.ERROR,
                message = "ICE $label invalide : doit contenir exactement 15 chiffres (reçu : '${ice}')"
            ))
            return
        }

        // ICE check digit validation (modulus 97 on first 13 digits)
        if (!validateIceCheckDigit(ice)) {
            issues.add(ValidationIssue(
                field = "ICE $label",
                severity = ValidationSeverity.WARNING,
                message = "ICE $label : la clé de contrôle semble incorrecte"
            ))
        }
    }

    private fun validateIceCheckDigit(ice: String): Boolean {
        // ICE format: 9 digits (company) + 4 digits (establishment) + 2 check digits
        // The check digits are computed using a modulus algorithm
        return try {
            val baseNumber = ice.substring(0, 13).toLong()
            val checkDigits = ice.substring(13).toInt()
            val computed = (97 - (baseNumber * 100 % 97).toInt()) % 97
            computed == checkDigits
        } catch (e: NumberFormatException) {
            false
        }
    }

    // --- IF Validation ---

    private fun validateIdentifiantFiscal(identifiantFiscal: String?, issues: MutableList<ValidationIssue>) {
        if (identifiantFiscal == null) {
            issues.add(ValidationIssue(
                field = "IF Fournisseur",
                severity = ValidationSeverity.WARNING,
                message = "Identifiant Fiscal (IF) non renseigné"
            ))
            return
        }

        // IF is typically 7-8 digits in Morocco
        if (!identifiantFiscal.matches(Regex("^\\d{7,8}$"))) {
            issues.add(ValidationIssue(
                field = "IF Fournisseur",
                severity = ValidationSeverity.ERROR,
                message = "Identifiant Fiscal (IF) invalide : doit contenir 7 ou 8 chiffres (reçu : '${identifiantFiscal}')"
            ))
        }
    }

    // --- RIB Validation ---

    private fun validateRib(rib: String?, issues: MutableList<ValidationIssue>) {
        if (rib == null) {
            issues.add(ValidationIssue(
                field = "RIB",
                severity = ValidationSeverity.INFO,
                message = "RIB bancaire non renseigné"
            ))
            return
        }

        // Moroccan RIB: exactly 24 digits
        if (!rib.matches(Regex("^\\d{24}$"))) {
            issues.add(ValidationIssue(
                field = "RIB",
                severity = ValidationSeverity.ERROR,
                message = "RIB invalide : doit contenir exactement 24 chiffres (reçu ${rib.length} caractères)"
            ))
            return
        }

        // Bank code validation (first 3 digits)
        val bankCode = rib.substring(0, 3)
        val knownBank = MOROCCAN_BANK_CODES[bankCode]
        if (knownBank != null) {
            issues.add(ValidationIssue(
                field = "RIB",
                severity = ValidationSeverity.INFO,
                message = "Banque identifiée : $knownBank (code $bankCode)"
            ))
        } else {
            issues.add(ValidationIssue(
                field = "RIB",
                severity = ValidationSeverity.WARNING,
                message = "Code banque inconnu : $bankCode"
            ))
        }

        // RIB key check (modulus 97)
        if (!validateRibKey(rib)) {
            issues.add(ValidationIssue(
                field = "RIB",
                severity = ValidationSeverity.ERROR,
                message = "Clé RIB invalide (erreur de contrôle modulo 97)"
            ))
        }
    }

    private fun validateRibKey(rib: String): Boolean {
        // RIB structure: bank(3) + city(3) + account(16) + key(2)
        return try {
            val bankCity = rib.substring(0, 6).toLong()
            val account = rib.substring(6, 22).toLong()
            val key = rib.substring(22, 24).toInt()
            // Modulus 97 check: (bankCity * 10^18 + account * 10^2 + key) mod 97 == 0
            val part1 = (bankCity % 97) * ((1_000_000_000_000_000_000L % 97).toInt()) % 97
            val part2 = (account % 97) * (100 % 97) % 97
            val total = (part1 + part2 + key) % 97
            total == 0L
        } catch (e: Exception) {
            // If numeric parsing fails, the RIB format is wrong
            false
        }
    }

    // --- TVA Rate Validation ---

    private fun validateTvaRate(tvaRate: BigDecimal?, issues: MutableList<ValidationIssue>) {
        if (tvaRate == null) {
            issues.add(ValidationIssue(
                field = "Taux TVA",
                severity = ValidationSeverity.WARNING,
                message = "Taux de TVA non renseigné"
            ))
            return
        }

        if (tvaRate !in LEGAL_TVA_RATES) {
            issues.add(ValidationIssue(
                field = "Taux TVA",
                severity = ValidationSeverity.ERROR,
                message = "Taux TVA invalide : ${tvaRate}%. Les taux légaux marocains sont : 0%, 7%, 10%, 14%, 20%"
            ))
        }
    }

    // --- Amount Cross-Validation ---

    private fun validateAmounts(invoice: Invoice, issues: MutableList<ValidationIssue>) {
        val ht = invoice.amountHt
        val tva = invoice.amountTva
        val ttc = invoice.amountTtc

        if (ht == null || ttc == null) {
            if (ht == null) {
                issues.add(ValidationIssue(
                    field = "Montant HT",
                    severity = ValidationSeverity.WARNING,
                    message = "Montant HT non renseigné"
                ))
            }
            if (ttc == null) {
                issues.add(ValidationIssue(
                    field = "Montant TTC",
                    severity = ValidationSeverity.ERROR,
                    message = "Montant TTC non renseigné"
                ))
            }
            return
        }

        // Cross-validate: HT + TVA = TTC (with tolerance of 0.01 for rounding)
        if (tva != null) {
            val expectedTtc = ht.add(tva)
            val diff = ttc.subtract(expectedTtc).abs()
            val tolerance = BigDecimal("0.01")

            if (diff > tolerance) {
                issues.add(ValidationIssue(
                    field = "Montants",
                    severity = ValidationSeverity.ERROR,
                    message = "Incohérence des montants : HT ($ht) + TVA ($tva) = ${expectedTtc}, " +
                            "mais TTC déclaré = $ttc (écart : $diff)"
                ))
            }
        }

        // Validate TVA amount matches rate
        if (tva != null && invoice.tvaRate != null && ht > BigDecimal.ZERO) {
            val expectedTva = ht.multiply(invoice.tvaRate).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            val tvaDiff = tva.subtract(expectedTva).abs()
            val tolerance = BigDecimal("0.10")

            if (tvaDiff > tolerance) {
                issues.add(ValidationIssue(
                    field = "Montant TVA",
                    severity = ValidationSeverity.WARNING,
                    message = "Le montant TVA ($tva) ne correspond pas au taux appliqué " +
                            "(${invoice.tvaRate}% de $ht = $expectedTva, écart : $tvaDiff)"
                ))
            }
        }

        // Discount validation
        if (invoice.discountPercent != null && invoice.discountAmount != null && ht > BigDecimal.ZERO) {
            val expectedDiscount = ht.multiply(invoice.discountPercent)
                .divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            val discountDiff = invoice.discountAmount!!.subtract(expectedDiscount).abs()
            if (discountDiff > BigDecimal("0.10")) {
                issues.add(ValidationIssue(
                    field = "Remise",
                    severity = ValidationSeverity.WARNING,
                    message = "Le montant de remise (${invoice.discountAmount}) ne correspond pas " +
                            "au pourcentage (${invoice.discountPercent}% de $ht = $expectedDiscount)"
                ))
            }
        }
    }

    // --- Line Items Consistency ---

    private fun validateLineItemsConsistency(invoice: Invoice, issues: MutableList<ValidationIssue>) {
        if (invoice.lineItems.isEmpty()) {
            issues.add(ValidationIssue(
                field = "Lignes",
                severity = ValidationSeverity.INFO,
                message = "Aucune ligne de détail extraite"
            ))
            return
        }

        // Sum of line totals HT should match invoice HT
        val lineHtSum = invoice.lineItems
            .mapNotNull { it.totalHt }
            .fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }

        if (invoice.amountHt != null && lineHtSum > BigDecimal.ZERO) {
            val diff = invoice.amountHt!!.subtract(lineHtSum).abs()
            val tolerance = BigDecimal("1.00") // Higher tolerance for line items due to rounding

            if (diff > tolerance) {
                issues.add(ValidationIssue(
                    field = "Lignes",
                    severity = ValidationSeverity.WARNING,
                    message = "La somme des lignes HT ($lineHtSum) ne correspond pas au montant " +
                            "HT global (${invoice.amountHt}) - écart : $diff"
                ))
            }
        }

        // Validate individual line items
        for (line in invoice.lineItems) {
            if (line.quantity != null && line.unitPriceHt != null && line.totalHt != null) {
                val expectedTotal = line.quantity!!.multiply(line.unitPriceHt)
                    .setScale(2, RoundingMode.HALF_UP)
                val lineDiff = line.totalHt!!.subtract(expectedTotal).abs()
                if (lineDiff > BigDecimal("0.10")) {
                    issues.add(ValidationIssue(
                        field = "Ligne ${line.lineNumber}",
                        severity = ValidationSeverity.WARNING,
                        message = "Ligne ${line.lineNumber} : quantité (${line.quantity}) x " +
                                "prix unitaire (${line.unitPriceHt}) = $expectedTotal, " +
                                "mais total HT déclaré = ${line.totalHt}"
                    ))
                }
            }
        }
    }

    // --- Currency Validation ---

    private fun validateCurrency(currency: String, issues: MutableList<ValidationIssue>) {
        if (currency !in SUPPORTED_CURRENCIES) {
            issues.add(ValidationIssue(
                field = "Devise",
                severity = ValidationSeverity.ERROR,
                message = "Devise non supportée : $currency. Devises acceptées : ${SUPPORTED_CURRENCIES.joinToString(", ")}"
            ))
        }
    }

    // --- Required Fields ---

    private fun validateRequiredFields(invoice: Invoice, issues: MutableList<ValidationIssue>) {
        if (invoice.invoiceNumber.isNullOrBlank()) {
            issues.add(ValidationIssue(
                field = "Numéro Facture",
                severity = ValidationSeverity.ERROR,
                message = "Numéro de facture manquant"
            ))
        }
        if (invoice.invoiceDate == null) {
            issues.add(ValidationIssue(
                field = "Date Facture",
                severity = ValidationSeverity.WARNING,
                message = "Date de facture non renseignée"
            ))
        }
        if (invoice.supplierName.isNullOrBlank()) {
            issues.add(ValidationIssue(
                field = "Fournisseur",
                severity = ValidationSeverity.ERROR,
                message = "Nom du fournisseur manquant"
            ))
        }
    }

    // --- Line Item TVA Rates ---

    private fun validateLineItemTvaRates(invoice: Invoice, issues: MutableList<ValidationIssue>) {
        for (line in invoice.lineItems) {
            if (line.tvaRate != null && line.tvaRate !in LEGAL_TVA_RATES) {
                issues.add(ValidationIssue(
                    field = "Ligne ${line.lineNumber} - TVA",
                    severity = ValidationSeverity.ERROR,
                    message = "Ligne ${line.lineNumber} : taux TVA invalide (${line.tvaRate}%). " +
                            "Les taux légaux marocains sont : 0%, 7%, 10%, 14%, 20%"
                ))
            }
        }
    }

    companion object {
        val LEGAL_TVA_RATES = setOf(
            BigDecimal("0"), BigDecimal("0.00"),
            BigDecimal("7"), BigDecimal("7.00"),
            BigDecimal("10"), BigDecimal("10.00"),
            BigDecimal("14"), BigDecimal("14.00"),
            BigDecimal("20"), BigDecimal("20.00")
        )

        val SUPPORTED_CURRENCIES = setOf("MAD", "EUR", "USD")

        val MOROCCAN_BANK_CODES = mapOf(
            "007" to "Attijariwafa Bank",
            "011" to "BMCE Bank (Bank of Africa)",
            "013" to "BMCI (BNP Paribas)",
            "021" to "Banque Populaire (CPM)",
            "022" to "Banque Populaire Régionale",
            "023" to "Banque Populaire Régionale",
            "025" to "Banque Populaire Régionale",
            "028" to "Banque Populaire Régionale",
            "050" to "Crédit du Maroc",
            "040" to "Société Générale Maroc",
            "060" to "CIH Bank",
            "070" to "Crédit Agricole du Maroc",
            "080" to "Bank Al-Maghrib",
            "090" to "CDG Capital",
            "145" to "Al Barid Bank",
            "150" to "CFG Bank",
            "160" to "Umnia Bank",
            "170" to "Bank Assafa",
            "180" to "BTI Bank",
            "190" to "Al Akhdar Bank"
        )
    }
}

// --- Result data classes ---

data class ValidationResult(
    val valid: Boolean,
    val errors: List<ValidationMessage>,
    val warnings: List<ValidationMessage>
) {
    companion object {
        fun fromIssues(invoiceId: Long?, valid: Boolean, issues: List<ValidationIssue>): ValidationResult {
            val errors = issues.filter { it.severity == ValidationSeverity.ERROR }
                .map { ValidationMessage(field = it.field, message = it.message, severity = it.severity.name) }
            val warnings = issues.filter { it.severity != ValidationSeverity.ERROR }
                .map { ValidationMessage(field = it.field, message = it.message, severity = it.severity.name) }
            return ValidationResult(valid = valid, errors = errors, warnings = warnings)
        }
    }
}

data class ValidationMessage(
    val field: String,
    val message: String,
    val severity: String
)

data class ValidationIssue(
    val field: String,
    val severity: ValidationSeverity,
    val message: String
)

enum class ValidationSeverity {
    ERROR,
    WARNING,
    INFO
}
