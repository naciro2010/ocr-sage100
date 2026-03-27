package com.ocrsage.service

import com.ocrsage.entity.Invoice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

@Service
class Sage100Service(
    @Value("\${sage100.base-url}") private val baseUrl: String,
    @Value("\${sage100.api-key}") private val apiKey: String,
    @Value("\${sage100.timeout}") private val timeout: Duration
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer $apiKey")
            .build()
    }

    fun syncInvoice(invoice: Invoice): SageSyncResult {
        log.info("Syncing invoice {} to Sage 100", invoice.invoiceNumber)

        val payload = mapOf(
            "reference" to invoice.invoiceNumber,
            "supplier" to invoice.supplierName,
            "date" to invoice.invoiceDate?.toString(),
            "amount_ht" to invoice.amountHt,
            "amount_tva" to invoice.amountTva,
            "amount_ttc" to invoice.amountTtc,
            "currency" to invoice.currency
        )

        return try {
            val response = webClient.post()
                .uri("/api/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(timeout)
                .block()

            val sageRef = response?.get("reference")?.toString()
            log.info("Invoice {} synced to Sage 100 with reference: {}", invoice.invoiceNumber, sageRef)
            SageSyncResult(success = true, reference = sageRef)
        } catch (e: Exception) {
            log.error("Failed to sync invoice {} to Sage 100: {}", invoice.invoiceNumber, e.message)
            SageSyncResult(success = false, error = e.message)
        }
    }
}

data class SageSyncResult(
    val success: Boolean,
    val reference: String? = null,
    val error: String? = null
)
