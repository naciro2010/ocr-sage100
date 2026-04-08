package com.madaef.recondoc.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.madaef.recondoc.dto.ExtractedInvoiceData
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

@Service
class AiExtractionService(
    private val objectMapper: ObjectMapper,
    private val appSettingsService: AppSettingsService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val SYSTEM_PROMPT = """
            Tu es un expert-comptable marocain specialise dans l'extraction de donnees de factures.
            Tu connais parfaitement la reglementation fiscale marocaine :
            - Les taux de TVA au Maroc : 0%, 7%, 10%, 14%, 20%
            - Les identifiants fiscaux : ICE (15 chiffres), IF (Identifiant Fiscal), RC (Registre de Commerce), Patente, CNSS
            - Les formats de factures marocaines (arabe et francais)
            - Le RIB bancaire marocain (24 chiffres)

            REGLES D'EXTRACTION :
            1. Extrais TOUTES les informations presentes, meme partielles
            2. L'ICE est un numero a 15 chiffres, souvent prefixe par "ICE:" ou "I.C.E."
            3. Le montant TTC = montant HT + TVA - remise eventuelle
            4. Si plusieurs taux de TVA, indique le taux principal dans tva_rate et detaille par ligne
            5. Les dates sont souvent au format DD/MM/YYYY — convertis en YYYY-MM-DD
            6. Extrais chaque ligne de la facture avec quantite, prix unitaire, TVA
            7. Si le texte est en arabe, extrais quand meme toutes les donnees
            8. Pour les montants, utilise des nombres decimaux (pas de formatage)

            REPONDS UNIQUEMENT avec un objet JSON valide, sans markdown, sans explication.
        """.trimIndent()

        val USER_PROMPT_TEMPLATE = """
            Extrais toutes les donnees de cette facture marocaine.
            Retourne un JSON avec cette structure exacte :

            {
              "supplier_name": "Raison sociale du fournisseur",
              "supplier_ice": "ICE a 15 chiffres",
              "supplier_if": "Identifiant Fiscal",
              "supplier_rc": "Registre de Commerce",
              "supplier_patente": "Numero de patente",
              "supplier_cnss": "Numero CNSS",
              "supplier_address": "Adresse complete",
              "supplier_city": "Ville",
              "client_name": "Nom du client (notre entreprise)",
              "client_ice": "ICE du client",
              "invoice_number": "Numero de facture",
              "invoice_date": "YYYY-MM-DD",
              "amount_ht": 0.00,
              "tva_rate": 20.00,
              "amount_tva": 0.00,
              "amount_ttc": 0.00,
              "discount_amount": null,
              "discount_percent": null,
              "currency": "MAD",
              "payment_method": "Virement/Cheque/Especes/Traite/Effet",
              "payment_due_date": "YYYY-MM-DD ou null",
              "bank_name": "Nom de la banque",
              "bank_rib": "RIB 24 chiffres",
              "line_items": [
                {
                  "line_number": 1,
                  "description": "Description du produit/service",
                  "quantity": 1.000,
                  "unit": "Unite/Kg/m2/Heure/Forfait",
                  "unit_price_ht": 0.00,
                  "tva_rate": 20.00,
                  "tva_amount": 0.00,
                  "total_ht": 0.00,
                  "total_ttc": 0.00
                }
              ]
            }

            Utilise null pour les champs non trouves. Les montants sont des nombres, pas des chaines.

            TEXTE DE LA FACTURE :
            %s
        """.trimIndent()
    }

    fun isAvailable(): Boolean {
        return appSettingsService.hasValidAiConfig()
    }

    fun extractInvoiceData(rawText: String): ExtractedInvoiceData {
        val apiKey = appSettingsService.getAiApiKey()
        val model = appSettingsService.getAiModel()
        val baseUrl = appSettingsService.getAiBaseUrl()

        if (apiKey.isBlank()) {
            throw RuntimeException("AI API key not configured")
        }

        log.info("Sending text to Claude API for extraction ({} chars, model={})", rawText.length, model)

        val webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("x-api-key", apiKey)
            .defaultHeader("anthropic-version", "2023-06-01")
            .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
            .build()

        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to 4096,
            "system" to SYSTEM_PROMPT,
            "messages" to listOf(
                mapOf("role" to "user", "content" to USER_PROMPT_TEMPLATE.format(rawText))
            )
        )

        val response = webClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(120))
            .block() ?: throw RuntimeException("Empty response from Claude API")

        @Suppress("UNCHECKED_CAST")
        val content = response["content"] as? List<Map<String, Any>>
            ?: throw RuntimeException("Unexpected response format from Claude API")

        val text = content.firstOrNull { it["type"] == "text" }?.get("text") as? String
            ?: throw RuntimeException("No text content in Claude API response")

        val cleanJson = text
            .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^```\\s*$", RegexOption.MULTILINE), "")
            .trim()

        log.info("Received structured data from Claude API")
        return objectMapper.readValue(cleanJson, ExtractedInvoiceData::class.java)
    }
}
