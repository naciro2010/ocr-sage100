package com.ocrsage.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.ocrsage.entity.Invoice
import com.ocrsage.repository.InvoiceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class ExportService(
    private val invoiceRepository: InvoiceRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /**
     * Export invoices to CSV with French headers, semicolon separator, UTF-8 BOM.
     */
    @Transactional(readOnly = true)
    fun exportToCsv(invoiceIds: List<Long>): ByteArray {
        val invoices = fetchInvoices(invoiceIds)
        log.info("Exporting {} invoices to CSV", invoices.size)

        val bos = ByteArrayOutputStream()
        // UTF-8 BOM for Excel compatibility
        bos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

        val writer = OutputStreamWriter(bos, StandardCharsets.UTF_8)
        writer.use { w ->
            // French headers
            w.write(CSV_HEADERS.joinToString(";"))
            w.write("\r\n")

            for (invoice in invoices) {
                val fields = listOf(
                    invoice.id?.toString() ?: "",
                    invoice.invoiceNumber ?: "",
                    invoice.invoiceDate?.format(dateFormatter) ?: "",
                    escapeCsvField(invoice.supplierName),
                    invoice.supplierIce ?: "",
                    invoice.supplierIf ?: "",
                    invoice.supplierRc ?: "",
                    invoice.supplierPatente ?: "",
                    invoice.supplierCnss ?: "",
                    escapeCsvField(invoice.supplierAddress),
                    invoice.supplierCity ?: "",
                    invoice.clientName ?: "",
                    invoice.clientIce ?: "",
                    invoice.amountHt?.toPlainString() ?: "",
                    invoice.tvaRate?.toPlainString() ?: "",
                    invoice.amountTva?.toPlainString() ?: "",
                    invoice.amountTtc?.toPlainString() ?: "",
                    invoice.discountAmount?.toPlainString() ?: "",
                    invoice.discountPercent?.toPlainString() ?: "",
                    invoice.currency,
                    invoice.paymentMethod ?: "",
                    invoice.paymentDueDate?.format(dateFormatter) ?: "",
                    invoice.bankName ?: "",
                    invoice.bankRib ?: "",
                    invoice.status.name,
                    if (invoice.sageSynced) "Oui" else "Non",
                    invoice.sageReference ?: "",
                    invoice.lineItems.size.toString()
                )
                w.write(fields.joinToString(";"))
                w.write("\r\n")
            }
        }

        return bos.toByteArray()
    }

    /**
     * Export invoices to pretty-printed JSON.
     */
    @Transactional(readOnly = true)
    fun exportToJson(invoiceIds: List<Long>): ByteArray {
        val invoices = fetchInvoices(invoiceIds)
        log.info("Exporting {} invoices to JSON", invoices.size)

        val exportData = invoices.map { invoice ->
            mapOf(
                "id" to invoice.id,
                "invoiceNumber" to invoice.invoiceNumber,
                "invoiceDate" to invoice.invoiceDate?.toString(),
                "supplier" to mapOf(
                    "name" to invoice.supplierName,
                    "ice" to invoice.supplierIce,
                    "identifiantFiscal" to invoice.supplierIf,
                    "rc" to invoice.supplierRc,
                    "patente" to invoice.supplierPatente,
                    "cnss" to invoice.supplierCnss,
                    "address" to invoice.supplierAddress,
                    "city" to invoice.supplierCity
                ),
                "client" to mapOf(
                    "name" to invoice.clientName,
                    "ice" to invoice.clientIce
                ),
                "amounts" to mapOf(
                    "amountHT" to invoice.amountHt,
                    "tvaRate" to invoice.tvaRate,
                    "amountTVA" to invoice.amountTva,
                    "amountTTC" to invoice.amountTtc,
                    "discountAmount" to invoice.discountAmount,
                    "discountPercent" to invoice.discountPercent,
                    "currency" to invoice.currency
                ),
                "payment" to mapOf(
                    "method" to invoice.paymentMethod,
                    "dueDate" to invoice.paymentDueDate?.toString(),
                    "bankName" to invoice.bankName,
                    "rib" to invoice.bankRib
                ),
                "lineItems" to invoice.lineItems.map { line ->
                    mapOf(
                        "lineNumber" to line.lineNumber,
                        "description" to line.description,
                        "quantity" to line.quantity,
                        "unit" to line.unit,
                        "unitPriceHT" to line.unitPriceHt,
                        "tvaRate" to line.tvaRate,
                        "tvaAmount" to line.tvaAmount,
                        "totalHT" to line.totalHt,
                        "totalTTC" to line.totalTtc
                    )
                },
                "status" to invoice.status.name,
                "sageSynced" to invoice.sageSynced,
                "sageReference" to invoice.sageReference,
                "createdAt" to invoice.createdAt.toString(),
                "updatedAt" to invoice.updatedAt.toString()
            )
        }

        return objectMapper.writeValueAsBytes(exportData)
    }

    /**
     * Export a single invoice to UBL 2.1 XML format (universal e-invoicing standard).
     */
    @Transactional(readOnly = true)
    fun exportToUblXml(invoiceId: Long): ByteArray {
        val invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow { NoSuchElementException("Invoice not found: $invoiceId") }
        log.info("Exporting invoice {} to UBL XML", invoiceId)

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"""")
            appendLine("""         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"""")
            appendLine("""         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">""")

            appendLine("  <cbc:UBLVersionID>2.1</cbc:UBLVersionID>")
            appendLine("  <cbc:ID>${escapeXml(invoice.invoiceNumber ?: "N/A")}</cbc:ID>")
            appendLine("  <cbc:IssueDate>${invoice.invoiceDate ?: LocalDate.now()}</cbc:IssueDate>")
            invoice.paymentDueDate?.let { appendLine("  <cbc:DueDate>$it</cbc:DueDate>") }
            appendLine("  <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>")
            appendLine("  <cbc:DocumentCurrencyCode>${invoice.currency}</cbc:DocumentCurrencyCode>")

            // Supplier party
            appendLine("  <cac:AccountingSupplierParty>")
            appendLine("    <cac:Party>")
            invoice.supplierName?.let {
                appendLine("      <cac:PartyName>")
                appendLine("        <cbc:Name>${escapeXml(it)}</cbc:Name>")
                appendLine("      </cac:PartyName>")
            }
            if (invoice.supplierAddress != null || invoice.supplierCity != null) {
                appendLine("      <cac:PostalAddress>")
                invoice.supplierAddress?.let { appendLine("        <cbc:StreetName>${escapeXml(it)}</cbc:StreetName>") }
                invoice.supplierCity?.let { appendLine("        <cbc:CityName>${escapeXml(it)}</cbc:CityName>") }
                appendLine("        <cac:Country><cbc:IdentificationCode>MA</cbc:IdentificationCode></cac:Country>")
                appendLine("      </cac:PostalAddress>")
            }
            // Moroccan fiscal identifiers
            appendLine("      <cac:PartyTaxScheme>")
            invoice.supplierIce?.let { appendLine("        <cbc:CompanyID schemeID=\"ICE\">$it</cbc:CompanyID>") }
            appendLine("        <cac:TaxScheme><cbc:ID>TVA</cbc:ID></cac:TaxScheme>")
            appendLine("      </cac:PartyTaxScheme>")
            appendLine("      <cac:PartyIdentification>")
            invoice.supplierIf?.let { appendLine("        <cbc:ID schemeID=\"IF\">$it</cbc:ID>") }
            appendLine("      </cac:PartyIdentification>")
            invoice.supplierRc?.let {
                appendLine("      <cac:PartyIdentification>")
                appendLine("        <cbc:ID schemeID=\"RC\">$it</cbc:ID>")
                appendLine("      </cac:PartyIdentification>")
            }
            invoice.supplierPatente?.let {
                appendLine("      <cac:PartyIdentification>")
                appendLine("        <cbc:ID schemeID=\"Patente\">$it</cbc:ID>")
                appendLine("      </cac:PartyIdentification>")
            }
            invoice.supplierCnss?.let {
                appendLine("      <cac:PartyIdentification>")
                appendLine("        <cbc:ID schemeID=\"CNSS\">$it</cbc:ID>")
                appendLine("      </cac:PartyIdentification>")
            }
            appendLine("    </cac:Party>")
            appendLine("  </cac:AccountingSupplierParty>")

            // Client party
            appendLine("  <cac:AccountingCustomerParty>")
            appendLine("    <cac:Party>")
            invoice.clientName?.let {
                appendLine("      <cac:PartyName>")
                appendLine("        <cbc:Name>${escapeXml(it)}</cbc:Name>")
                appendLine("      </cac:PartyName>")
            }
            invoice.clientIce?.let {
                appendLine("      <cac:PartyTaxScheme>")
                appendLine("        <cbc:CompanyID schemeID=\"ICE\">$it</cbc:CompanyID>")
                appendLine("        <cac:TaxScheme><cbc:ID>TVA</cbc:ID></cac:TaxScheme>")
                appendLine("      </cac:PartyTaxScheme>")
            }
            appendLine("    </cac:Party>")
            appendLine("  </cac:AccountingCustomerParty>")

            // Payment means
            invoice.paymentMethod?.let {
                appendLine("  <cac:PaymentMeans>")
                appendLine("    <cbc:PaymentMeansCode>${mapPaymentMethodToCode(it)}</cbc:PaymentMeansCode>")
                invoice.bankRib?.let { rib ->
                    appendLine("    <cac:PayeeFinancialAccount>")
                    appendLine("      <cbc:ID>$rib</cbc:ID>")
                    invoice.bankName?.let { bank -> appendLine("      <cbc:Name>${escapeXml(bank)}</cbc:Name>") }
                    appendLine("    </cac:PayeeFinancialAccount>")
                }
                appendLine("  </cac:PaymentMeans>")
            }

            // Tax total
            appendLine("  <cac:TaxTotal>")
            appendLine("    <cbc:TaxAmount currencyID=\"${invoice.currency}\">${invoice.amountTva ?: "0.00"}</cbc:TaxAmount>")
            appendLine("    <cac:TaxSubtotal>")
            appendLine("      <cbc:TaxableAmount currencyID=\"${invoice.currency}\">${invoice.amountHt ?: "0.00"}</cbc:TaxableAmount>")
            appendLine("      <cbc:TaxAmount currencyID=\"${invoice.currency}\">${invoice.amountTva ?: "0.00"}</cbc:TaxAmount>")
            appendLine("      <cac:TaxCategory>")
            appendLine("        <cbc:Percent>${invoice.tvaRate ?: "20.00"}</cbc:Percent>")
            appendLine("        <cac:TaxScheme><cbc:ID>TVA</cbc:ID></cac:TaxScheme>")
            appendLine("      </cac:TaxCategory>")
            appendLine("    </cac:TaxSubtotal>")
            appendLine("  </cac:TaxTotal>")

            // Legal monetary total
            appendLine("  <cac:LegalMonetaryTotal>")
            appendLine("    <cbc:LineExtensionAmount currencyID=\"${invoice.currency}\">${invoice.amountHt ?: "0.00"}</cbc:LineExtensionAmount>")
            appendLine("    <cbc:TaxExclusiveAmount currencyID=\"${invoice.currency}\">${invoice.amountHt ?: "0.00"}</cbc:TaxExclusiveAmount>")
            appendLine("    <cbc:TaxInclusiveAmount currencyID=\"${invoice.currency}\">${invoice.amountTtc ?: "0.00"}</cbc:TaxInclusiveAmount>")
            invoice.discountAmount?.let {
                appendLine("    <cbc:AllowanceTotalAmount currencyID=\"${invoice.currency}\">$it</cbc:AllowanceTotalAmount>")
            }
            appendLine("    <cbc:PayableAmount currencyID=\"${invoice.currency}\">${invoice.amountTtc ?: "0.00"}</cbc:PayableAmount>")
            appendLine("  </cac:LegalMonetaryTotal>")

            // Invoice lines
            for (line in invoice.lineItems) {
                appendLine("  <cac:InvoiceLine>")
                appendLine("    <cbc:ID>${line.lineNumber}</cbc:ID>")
                appendLine("    <cbc:InvoicedQuantity unitCode=\"${line.unit ?: "EA"}\">${line.quantity ?: "1"}</cbc:InvoicedQuantity>")
                appendLine("    <cbc:LineExtensionAmount currencyID=\"${invoice.currency}\">${line.totalHt ?: "0.00"}</cbc:LineExtensionAmount>")
                appendLine("    <cac:Item>")
                appendLine("      <cbc:Description>${escapeXml(line.description)}</cbc:Description>")
                appendLine("      <cac:ClassifiedTaxCategory>")
                appendLine("        <cbc:Percent>${line.tvaRate ?: "20.00"}</cbc:Percent>")
                appendLine("        <cac:TaxScheme><cbc:ID>TVA</cbc:ID></cac:TaxScheme>")
                appendLine("      </cac:ClassifiedTaxCategory>")
                appendLine("    </cac:Item>")
                appendLine("    <cac:Price>")
                appendLine("      <cbc:PriceAmount currencyID=\"${invoice.currency}\">${line.unitPriceHt ?: "0.00"}</cbc:PriceAmount>")
                appendLine("    </cac:Price>")
                appendLine("  </cac:InvoiceLine>")
            }

            appendLine("</Invoice>")
        }

        return xml.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Export a single invoice to simplified EDI INVOIC D96A format.
     */
    @Transactional(readOnly = true)
    fun exportToEdiFactur(invoiceId: Long): ByteArray {
        val invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow { NoSuchElementException("Invoice not found: $invoiceId") }
        log.info("Exporting invoice {} to EDI INVOIC", invoiceId)

        val segmentSeparator = "'"
        val elementSeparator = "+"
        val componentSeparator = ":"

        val edi = buildString {
            // Interchange header
            appendLine("UNB${elementSeparator}UNOC${componentSeparator}3${elementSeparator}" +
                    "${escapeEdi(invoice.supplierIce ?: "SENDER")}${elementSeparator}" +
                    "${escapeEdi(invoice.clientIce ?: "RECEIVER")}${elementSeparator}" +
                    "${formatEdiDate(invoice.invoiceDate)}${componentSeparator}0000${elementSeparator}" +
                    "${invoice.id}$segmentSeparator")

            // Message header
            appendLine("UNH${elementSeparator}1${elementSeparator}" +
                    "INVOIC${componentSeparator}D${componentSeparator}96A${componentSeparator}UN$segmentSeparator")

            // Beginning of message
            appendLine("BGM${elementSeparator}380${elementSeparator}" +
                    "${escapeEdi(invoice.invoiceNumber ?: "")}${elementSeparator}9$segmentSeparator")

            // Invoice date
            appendLine("DTM${elementSeparator}137${componentSeparator}" +
                    "${formatEdiDate(invoice.invoiceDate)}${componentSeparator}102$segmentSeparator")

            // Payment due date
            invoice.paymentDueDate?.let {
                appendLine("DTM${elementSeparator}13${componentSeparator}" +
                        "${formatEdiDate(it)}${componentSeparator}102$segmentSeparator")
            }

            // Supplier (SU)
            appendLine("NAD${elementSeparator}SU${elementSeparator}" +
                    "${escapeEdi(invoice.supplierIce ?: "")}${componentSeparator}${componentSeparator}160${elementSeparator}" +
                    "${elementSeparator}${escapeEdi(invoice.supplierName ?: "")}${elementSeparator}" +
                    "${escapeEdi(invoice.supplierAddress ?: "")}${elementSeparator}" +
                    "${escapeEdi(invoice.supplierCity ?: "")}$segmentSeparator")

            // Supplier fiscal identifiers
            invoice.supplierIce?.let {
                appendLine("RFF${elementSeparator}VA${componentSeparator}$it$segmentSeparator")
            }
            invoice.supplierIf?.let {
                appendLine("RFF${elementSeparator}GN${componentSeparator}$it$segmentSeparator")
            }
            invoice.supplierRc?.let {
                appendLine("RFF${elementSeparator}AHP${componentSeparator}$it$segmentSeparator")
            }
            invoice.supplierPatente?.let {
                appendLine("RFF${elementSeparator}AHQ${componentSeparator}$it$segmentSeparator")
            }
            invoice.supplierCnss?.let {
                appendLine("RFF${elementSeparator}ABO${componentSeparator}$it$segmentSeparator")
            }

            // Buyer (BY)
            appendLine("NAD${elementSeparator}BY${elementSeparator}" +
                    "${escapeEdi(invoice.clientIce ?: "")}${componentSeparator}${componentSeparator}160${elementSeparator}" +
                    "${elementSeparator}${escapeEdi(invoice.clientName ?: "")}$segmentSeparator")

            // Currency
            appendLine("CUX${elementSeparator}2${componentSeparator}${invoice.currency}${componentSeparator}4$segmentSeparator")

            // Payment method
            invoice.paymentMethod?.let {
                appendLine("PAT${elementSeparator}1$segmentSeparator")
                appendLine("PCD${elementSeparator}${mapPaymentMethodToEdi(it)}$segmentSeparator")
            }

            // Bank details
            invoice.bankRib?.let { rib ->
                appendLine("FII${elementSeparator}BF${elementSeparator}$rib${elementSeparator}" +
                        "${escapeEdi(invoice.bankName ?: "")}$segmentSeparator")
            }

            // Line items
            var lineCount = 0
            for (line in invoice.lineItems) {
                lineCount++
                appendLine("LIN${elementSeparator}$lineCount$segmentSeparator")
                appendLine("IMD${elementSeparator}F${elementSeparator}${elementSeparator}" +
                        "${componentSeparator}${componentSeparator}${componentSeparator}" +
                        "${escapeEdi(line.description ?: "")}$segmentSeparator")
                appendLine("QTY${elementSeparator}47${componentSeparator}${line.quantity ?: "1"}${componentSeparator}" +
                        "${line.unit ?: "EA"}$segmentSeparator")
                appendLine("MOA${elementSeparator}203${componentSeparator}${line.totalHt ?: "0.00"}$segmentSeparator")
                appendLine("PRI${elementSeparator}AAA${componentSeparator}${line.unitPriceHt ?: "0.00"}$segmentSeparator")
                line.tvaRate?.let { rate ->
                    appendLine("TAX${elementSeparator}7${elementSeparator}VAT${elementSeparator}" +
                            "${elementSeparator}${elementSeparator}$rate$segmentSeparator")
                }
            }

            // Totals section
            appendLine("UNS${elementSeparator}S$segmentSeparator")

            // Amount HT
            appendLine("MOA${elementSeparator}79${componentSeparator}${invoice.amountHt ?: "0.00"}$segmentSeparator")

            // Amount TVA
            appendLine("MOA${elementSeparator}124${componentSeparator}${invoice.amountTva ?: "0.00"}$segmentSeparator")

            // Amount TTC
            appendLine("MOA${elementSeparator}86${componentSeparator}${invoice.amountTtc ?: "0.00"}$segmentSeparator")

            // Discount
            invoice.discountAmount?.let {
                appendLine("MOA${elementSeparator}260${componentSeparator}$it$segmentSeparator")
            }

            // Tax summary
            appendLine("TAX${elementSeparator}7${elementSeparator}VAT${elementSeparator}" +
                    "${elementSeparator}${elementSeparator}${invoice.tvaRate ?: "20.00"}$segmentSeparator")
            appendLine("MOA${elementSeparator}124${componentSeparator}${invoice.amountTva ?: "0.00"}$segmentSeparator")

            // Message trailer
            val segmentCount = this.lines().size
            appendLine("UNT${elementSeparator}$segmentCount${elementSeparator}1$segmentSeparator")
            appendLine("UNZ${elementSeparator}1${elementSeparator}${invoice.id}$segmentSeparator")
        }

        return edi.toByteArray(StandardCharsets.UTF_8)
    }

    // --- Private helpers ---

    private fun fetchInvoices(invoiceIds: List<Long>): List<Invoice> {
        val invoices = invoiceRepository.findAllById(invoiceIds)
        if (invoices.isEmpty()) {
            throw NoSuchElementException("No invoices found for the given IDs")
        }
        return invoices
    }

    private fun escapeCsvField(value: String?): String {
        if (value == null) return ""
        return if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun escapeXml(value: String?): String {
        if (value == null) return ""
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun escapeEdi(value: String): String {
        return value
            .replace("+", " ")
            .replace(":", " ")
            .replace("'", " ")
    }

    private fun formatEdiDate(date: LocalDate?): String {
        return date?.format(DateTimeFormatter.ofPattern("yyyyMMdd")) ?: "00000000"
    }

    private fun mapPaymentMethodToCode(method: String): String {
        return when (method.lowercase()) {
            "virement" -> "30"
            "cheque", "chèque" -> "20"
            "especes", "espèces" -> "10"
            "traite" -> "31"
            "effet" -> "31"
            "lcn" -> "31"
            "carte" -> "48"
            "prelevement", "prélèvement" -> "49"
            "compensation" -> "97"
            else -> "1"
        }
    }

    private fun mapPaymentMethodToEdi(method: String): String {
        return when (method.lowercase()) {
            "virement" -> "10"
            "cheque", "chèque" -> "20"
            "especes", "espèces" -> "1"
            "traite", "effet", "lcn" -> "21"
            "carte" -> "42"
            "prelevement", "prélèvement" -> "33"
            "compensation" -> "97"
            else -> "ZZZ"
        }
    }

    companion object {
        private val CSV_HEADERS = listOf(
            "ID",
            "Numéro Facture",
            "Date Facture",
            "Fournisseur",
            "ICE Fournisseur",
            "IF Fournisseur",
            "RC Fournisseur",
            "Patente",
            "CNSS",
            "Adresse Fournisseur",
            "Ville",
            "Client",
            "ICE Client",
            "Montant HT",
            "Taux TVA (%)",
            "Montant TVA",
            "Montant TTC",
            "Remise (montant)",
            "Remise (%)",
            "Devise",
            "Mode de Paiement",
            "Date Échéance",
            "Banque",
            "RIB",
            "Statut",
            "Synchronisé Sage",
            "Référence Sage",
            "Nombre de Lignes"
        )
    }
}
