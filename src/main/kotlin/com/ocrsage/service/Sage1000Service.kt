package com.ocrsage.service

import com.ocrsage.entity.Invoice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * Connector for Sage 1000 via Objets Métiers REST API.
 * Sage 1000 exposes business objects through a .NET REST service layer.
 */
@Service
class Sage1000Service(
    @Value("\${sage1000.base-url}") private val baseUrl: String,
    @Value("\${sage1000.api-key}") private val apiKey: String,
    @Value("\${sage1000.timeout}") private val timeout: Duration,
    @Value("\${sage1000.company-code}") private val companyCode: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer $apiKey")
            .defaultHeader("X-Company-Code", companyCode)
            .build()
    }

    fun syncInvoice(invoice: Invoice): SageSyncResult {
        log.info("Syncing invoice {} to Sage 1000 (company: {})", invoice.invoiceNumber, companyCode)

        // Sage 1000 Objets Métiers - Écriture comptable achat
        val lineItems = invoice.lineItems.map { line ->
            mapOf(
                "Description" to (line.description ?: ""),
                "Quantite" to line.quantity,
                "PrixUnitaireHT" to line.unitPriceHt,
                "TauxTVA" to line.tvaRate,
                "MontantHT" to line.totalHt,
                "MontantTVA" to line.tvaAmount,
                "MontantTTC" to line.totalTtc
            )
        }

        val payload = mapOf(
            "TypeDocument" to "FA",  // Facture Achat
            "Reference" to invoice.invoiceNumber,
            "DatePiece" to invoice.invoiceDate?.toString(),
            "DateEcheance" to invoice.paymentDueDate?.toString(),
            // Fournisseur
            "Fournisseur" to mapOf(
                "RaisonSociale" to invoice.supplierName,
                "ICE" to invoice.supplierIce,
                "IF" to invoice.supplierIf,
                "RC" to invoice.supplierRc,
                "Patente" to invoice.supplierPatente,
                "Adresse" to invoice.supplierAddress,
                "Ville" to invoice.supplierCity
            ),
            // Montants
            "MontantHT" to invoice.amountHt,
            "TauxTVA" to invoice.tvaRate,
            "MontantTVA" to invoice.amountTva,
            "MontantTTC" to invoice.amountTtc,
            "Remise" to invoice.discountAmount,
            "Devise" to invoice.currency,
            // Règlement
            "ModeReglement" to invoice.paymentMethod,
            "Banque" to invoice.bankName,
            "RIB" to invoice.bankRib,
            // Lignes
            "Lignes" to lineItems
        )

        return try {
            val response = webClient.post()
                .uri("/api/objets-metiers/ecritures-achat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(timeout)
                .block()

            val sageRef = response?.get("NumeroEcriture")?.toString()
            log.info("Invoice {} synced to Sage 1000 with ref: {}", invoice.invoiceNumber, sageRef)
            SageSyncResult(success = true, reference = sageRef)
        } catch (e: Exception) {
            log.error("Failed to sync invoice {} to Sage 1000: {}", invoice.invoiceNumber, e.message)
            SageSyncResult(success = false, error = e.message)
        }
    }
}

data class SageSyncResult(
    val success: Boolean,
    val reference: String? = null,
    val error: String? = null
)
