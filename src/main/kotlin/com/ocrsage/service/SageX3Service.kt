package com.ocrsage.service

import com.ocrsage.entity.Invoice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * Connector for Sage X3 (Sage Enterprise Management) via Syracuse REST/OData web services.
 * Sage X3 uses a folder-based multi-company architecture with OAuth2 authentication.
 */
@Service
class SageX3Service(
    @Value("\${sagex3.base-url:http://localhost:8124}") private val baseUrl: String,
    @Value("\${sagex3.client-id:}") private val clientId: String,
    @Value("\${sagex3.client-secret:}") private val clientSecret: String,
    @Value("\${sagex3.folder:SEED}") private val folder: String,
    @Value("\${sagex3.pool-alias:SEED}") private val poolAlias: String,
    @Value("\${sagex3.timeout:30s}") private val timeout: Duration
) : ErpConnector {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Sage-FolderId", folder)
            .defaultHeader("X-Sage-PoolAlias", poolAlias)
            .build()
    }

    /**
     * Obtains an OAuth2 access token using client_credentials grant.
     */
    private fun obtainAccessToken(): String {
        log.debug("Requesting OAuth2 token from Sage X3 Syracuse server")

        val tokenResponse = WebClient.builder()
            .baseUrl(baseUrl)
            .build()
            .post()
            .uri("/auth/oauth/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("grant_type=client_credentials&client_id=$clientId&client_secret=$clientSecret")
            .retrieve()
            .bodyToMono(Map::class.java)
            .timeout(timeout)
            .block() ?: throw RuntimeException("Empty token response from Sage X3")

        return tokenResponse["access_token"]?.toString()
            ?: throw RuntimeException("No access_token in Sage X3 OAuth2 response")
    }

    override fun syncInvoice(invoice: Invoice): SageSyncResult {
        log.info(
            "Syncing invoice {} to Sage X3 (folder: {}, pool: {})",
            invoice.invoiceNumber, folder, poolAlias
        )

        val payload = buildX3PurchaseInvoicePayload(invoice)

        return try {
            val accessToken = obtainAccessToken()

            val response = webClient.post()
                .uri("/api/x3/erp/$folder/SPURCHASE")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $accessToken")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(timeout)
                .block()

            @Suppress("UNCHECKED_CAST")
            val status = (response?.get("\$status") as? Map<String, Any>)
            val diagnosis = status?.get("\$diagnosis")
            val severity = when (diagnosis) {
                is List<*> -> (diagnosis.firstOrNull() as? Map<*, *>)?.get("\$severity")?.toString()
                else -> null
            }

            if (severity == "error") {
                val message = when (diagnosis) {
                    is List<*> -> (diagnosis.firstOrNull() as? Map<*, *>)?.get("\$message")?.toString()
                    else -> "Unknown X3 error"
                }
                log.error("Sage X3 rejected invoice {}: {}", invoice.invoiceNumber, message)
                SageSyncResult(success = false, error = "Sage X3: $message")
            } else {
                val sageRef = response?.get("SIVTYP_0")?.toString()
                    ?: response?.get("NUM_0")?.toString()
                log.info("Invoice {} synced to Sage X3 with ref: {}", invoice.invoiceNumber, sageRef)
                SageSyncResult(success = true, reference = sageRef)
            }
        } catch (e: Exception) {
            log.error("Failed to sync invoice {} to Sage X3: {}", invoice.invoiceNumber, e.message)
            SageSyncResult(success = false, error = e.message)
        }
    }

    override fun testConnection(): Boolean {
        return try {
            val accessToken = obtainAccessToken()

            val response = webClient.get()
                .uri("/api/x3/erp/$folder/\$ping")
                .header("Authorization", "Bearer $accessToken")
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(timeout)
                .block()

            log.info("Sage X3 connection test successful for folder: {}", folder)
            response != null
        } catch (e: Exception) {
            log.error("Sage X3 connection test failed: {}", e.message)
            false
        }
    }

    /**
     * Builds the Sage X3 purchase invoice payload following the SPURCHASE representation.
     * X3 uses numbered field suffixes (BPSINV0_1, BPSINV0_2, etc.) for header and line blocks.
     */
    private fun buildX3PurchaseInvoicePayload(invoice: Invoice): Map<String, Any?> {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        // Line items mapped to X3 SPURCHASE detail lines (BPSINV1 sublist)
        val detailLines = invoice.lineItems.map { line ->
            mapOf(
                "ITMREF_0" to (line.description?.take(20) ?: "ITEM"),  // Article reference
                "ITMDES_0" to (line.description ?: ""),                 // Description
                "QTY_0" to line.quantity,                               // Quantity
                "NETPRI_0" to line.unitPriceHt,                         // Unit price HT
                "VACITM_0" to mapTvaRateToX3TaxCode(line.tvaRate),     // Tax code
                "AMTNOTLIN_0" to line.totalHt,                          // Line amount HT
                "AMTLIN_0" to line.totalTtc                             // Line amount TTC
            )
        }

        return mapOf(
            // Header block (BPSINV0_1)
            "SIVTYP_0" to "INV",                                        // Invoice type
            "NUM_0" to "",                                               // Auto-generated number
            "BPSNUM_0" to (invoice.supplierName?.take(15) ?: ""),       // Supplier code
            "BPAINV_0" to (invoice.supplierName?.take(15) ?: ""),       // Invoice address
            "INVREF_0" to (invoice.invoiceNumber ?: ""),                 // Supplier invoice ref
            "INVDAT_0" to invoice.invoiceDate?.format(dateFormatter),    // Invoice date
            "ACCDAT_0" to invoice.invoiceDate?.format(dateFormatter),    // Accounting date
            "BPRVCR_0" to (invoice.invoiceNumber ?: ""),                 // External reference
            "CUR_0" to invoice.currency,                                 // Currency

            // Supplier fiscal identifiers (Moroccan-specific, BPSINV0_2)
            "XX_ICE_0" to (invoice.supplierIce ?: ""),                   // ICE (15 digits)
            "XX_IF_0" to (invoice.supplierIf ?: ""),                     // Identifiant Fiscal
            "XX_RC_0" to (invoice.supplierRc ?: ""),                     // Registre de Commerce
            "XX_PATENTE_0" to (invoice.supplierPatente ?: ""),           // Patente
            "XX_CNSS_0" to (invoice.supplierCnss ?: ""),                 // CNSS

            // Payment info
            "PTE_0" to mapPaymentMethodToX3(invoice.paymentMethod),      // Payment terms
            "STRDUDDAT_0" to invoice.paymentDueDate?.format(dateFormatter),
            "XX_RIB_0" to (invoice.bankRib ?: ""),                       // RIB (24 digits)
            "XX_BANQUE_0" to (invoice.bankName ?: ""),                   // Bank name

            // Amounts
            "AMTATI_0" to invoice.amountTtc,                             // Total TTC
            "AMTNOT_0" to invoice.amountHt,                              // Total HT
            "AMTTAX_0" to invoice.amountTva,                             // Total TVA
            "DISCRGVAL_0" to invoice.discountAmount,                     // Discount

            // Detail lines
            "BPSINV1" to detailLines
        )
    }

    /**
     * Maps Moroccan TVA rates to Sage X3 tax codes.
     */
    private fun mapTvaRateToX3TaxCode(tvaRate: java.math.BigDecimal?): String {
        return when (tvaRate?.toInt()) {
            0 -> "EXO"      // Exonéré
            7 -> "TVA7"     // 7% TVA réduit
            10 -> "TVA10"   // 10% TVA intermédiaire
            14 -> "TVA14"   // 14% TVA intermédiaire
            20 -> "TVA20"   // 20% TVA normal
            else -> "TVA20" // Default to standard rate
        }
    }

    /**
     * Maps payment method strings to Sage X3 payment terms codes.
     */
    private fun mapPaymentMethodToX3(paymentMethod: String?): String {
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
