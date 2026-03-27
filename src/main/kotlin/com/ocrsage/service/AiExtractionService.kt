package com.ocrsage.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ocrsage.dto.ExtractedInvoiceData
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class AiExtractionService(
    private val objectMapper: ObjectMapper,
    @Value("\${claude.api-key}") private val apiKey: String,
    @Value("\${claude.model}") private val model: String,
    @Value("\${claude.base-url}") private val baseUrl: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("x-api-key", apiKey)
            .defaultHeader("anthropic-version", "2023-06-01")
            .build()
    }

    fun extractInvoiceData(rawText: String): ExtractedInvoiceData {
        log.info("Sending text to Claude API for structured extraction ({} chars)", rawText.length)

        val prompt = """
            Analyse le texte suivant extrait d'une facture et retourne UNIQUEMENT un objet JSON avec ces champs :
            - supplier_name: nom du fournisseur
            - invoice_number: numéro de la facture
            - invoice_date: date de la facture au format YYYY-MM-DD
            - amount_ht: montant hors taxes (nombre décimal)
            - amount_tva: montant de la TVA (nombre décimal)
            - amount_ttc: montant TTC (nombre décimal)
            - currency: devise (MAD, EUR, USD, etc.)

            Si un champ n'est pas trouvé, utilise null.
            Réponds UNIQUEMENT avec le JSON, sans markdown ni explication.

            Texte de la facture :
            $rawText
        """.trimIndent()

        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to 1024,
            "messages" to listOf(
                mapOf("role" to "user", "content" to prompt)
            )
        )

        val response = webClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block() ?: throw RuntimeException("Empty response from Claude API")

        @Suppress("UNCHECKED_CAST")
        val content = response["content"] as? List<Map<String, Any>>
            ?: throw RuntimeException("Unexpected response format from Claude API")

        val text = content.firstOrNull { it["type"] == "text" }?.get("text") as? String
            ?: throw RuntimeException("No text content in Claude API response")

        log.info("Received structured data from Claude API")
        return objectMapper.readValue(text, ExtractedInvoiceData::class.java)
    }
}
