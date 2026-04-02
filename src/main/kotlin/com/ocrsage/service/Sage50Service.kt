package com.ocrsage.service

import com.ocrsage.entity.Invoice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalDate
import java.util.Base64

/**
 * Connector for Sage 50 (Ciel Compta) via the on-premise REST Bridge gateway.
 * Sage 50 uses Basic authentication and a company-file based architecture.
 */
@Service
class Sage50Service(
    @Value("\${sage50.base-url:http://localhost:9150}") private val baseUrl: String,
    @Value("\${sage50.username:}") private val username: String,
    @Value("\${sage50.password:}") private val password: String,
    @Value("\${sage50.company-file:}") private val companyFile: String,
    @Value("\${sage50.journal-code:ACH}") private val journalCode: String,
    @Value("\${sage50.timeout:30s}") private val timeout: Duration
) : ErpConnector {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        val credentials = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Basic $credentials")
            .defaultHeader("X-Company-File", companyFile)
            .build()
    }

    override fun syncInvoice(invoice: Invoice): SageSyncResult {
        log.info(
            "Syncing invoice {} to Sage 50 (company: {}, journal: {})",
            invoice.invoiceNumber, companyFile, journalCode
        )

        val payload = buildSage50PurchaseInvoicePayload(invoice)

        return try {
            val response = webClient.post()
                .uri("/api/v1/purchase-invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(timeout)
                .block()

            val sageRef = response?.get("invoiceId")?.toString()
                ?: response?.get("documentNumber")?.toString()
            log.info("Invoice {} synced to Sage 50 with ref: {}", invoice.invoiceNumber, sageRef)
            SageSyncResult(success = true, reference = sageRef)
        } catch (e: Exception) {
            log.error("Failed to sync invoice {} to Sage 50: {}", invoice.invoiceNumber, e.message)
            SageSyncResult(success = false, error = e.message)
        }
    }

    override fun testConnection(): Boolean {
        return try {
            val response = webClient.get()
                .uri("/api/v1/ping")
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(timeout)
                .block()

            log.info("Sage 50 connection test successful for company: {}", companyFile)
            response != null
        } catch (e: Exception) {
            log.error("Sage 50 connection test failed: {}", e.message)
            false
        }
    }

    /**
     * Builds the Sage 50 (Ciel Compta) purchase invoice payload.
     * Sage 50 uses a simpler flat structure with journal-based accounting.
     */
    private fun buildSage50PurchaseInvoicePayload(invoice: Invoice): Map<String, Any?> {
        val fiscalYear = determineFiscalYear(invoice.invoiceDate)

        val lineItems = invoice.lineItems.map { line ->
            mapOf(
                "lineNumber" to line.lineNumber,
                "description" to (line.description ?: ""),
                "accountCode" to resolveAccountCode(line.tvaRate),
                "quantity" to line.quantity,
                "unitPrice" to line.unitPriceHt,
                "vatCode" to mapTvaRateToSage50VatCode(line.tvaRate),
                "vatRate" to line.tvaRate,
                "vatAmount" to line.tvaAmount,
                "amountExclVat" to line.totalHt,
                "amountInclVat" to line.totalTtc
            )
        }

        return mapOf(
            // Document header
            "documentType" to "PURCHASE_INVOICE",
            "journalCode" to journalCode,
            "fiscalYear" to fiscalYear,
            "documentNumber" to (invoice.invoiceNumber ?: ""),
            "documentDate" to invoice.invoiceDate?.toString(),
            "dueDate" to invoice.paymentDueDate?.toString(),
            "reference" to (invoice.invoiceNumber ?: ""),

            // Supplier information
            "supplier" to mapOf(
                "name" to invoice.supplierName,
                "ice" to invoice.supplierIce,
                "identifiantFiscal" to invoice.supplierIf,
                "registreCommerce" to invoice.supplierRc,
                "patente" to invoice.supplierPatente,
                "cnss" to invoice.supplierCnss,
                "address" to invoice.supplierAddress,
                "city" to invoice.supplierCity
            ),

            // Amounts
            "totalExclVat" to invoice.amountHt,
            "totalVat" to invoice.amountTva,
            "totalInclVat" to invoice.amountTtc,
            "discountAmount" to invoice.discountAmount,
            "discountPercent" to invoice.discountPercent,
            "currency" to invoice.currency,

            // Payment
            "paymentMethod" to mapPaymentMethodToSage50(invoice.paymentMethod),
            "bankName" to invoice.bankName,
            "rib" to invoice.bankRib,

            // Line items
            "lines" to lineItems
        )
    }

    /**
     * Determines the fiscal year from the invoice date.
     * Moroccan fiscal year typically follows the calendar year (Jan-Dec).
     */
    private fun determineFiscalYear(invoiceDate: LocalDate?): Int {
        return invoiceDate?.year ?: LocalDate.now().year
    }

    /**
     * Maps Moroccan TVA rates to Sage 50 VAT codes.
     */
    private fun mapTvaRateToSage50VatCode(tvaRate: java.math.BigDecimal?): String {
        return when (tvaRate?.toInt()) {
            0 -> "EXO"
            7 -> "R07"
            10 -> "R10"
            14 -> "R14"
            20 -> "N20"
            else -> "N20"
        }
    }

    /**
     * Resolves the purchase accounting code based on VAT rate.
     * Uses standard Moroccan chart of accounts (Plan Comptable Marocain).
     */
    private fun resolveAccountCode(tvaRate: java.math.BigDecimal?): String {
        return when (tvaRate?.toInt()) {
            0 -> "6111000"    // Achats de marchandises exonérés
            7 -> "6111007"    // Achats TVA 7%
            10 -> "6111010"   // Achats TVA 10%
            14 -> "6111014"   // Achats TVA 14%
            20 -> "6111020"   // Achats TVA 20%
            else -> "6111000" // Default
        }
    }

    /**
     * Maps payment method strings to Sage 50 payment type codes.
     */
    private fun mapPaymentMethodToSage50(paymentMethod: String?): String {
        return when (paymentMethod?.lowercase()) {
            "virement" -> "VIR"
            "chèque", "cheque" -> "CHQ"
            "espèces", "especes" -> "ESP"
            "traite" -> "TRT"
            "effet" -> "EFF"
            "lcn" -> "LCN"
            "carte" -> "CB"
            "prélèvement", "prelevement" -> "PRV"
            "compensation" -> "CMP"
            else -> "VIR"
        }
    }
}
