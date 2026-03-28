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
            .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
            .build()
    }

    companion object {
        val SYSTEM_PROMPT = """
            Tu es un expert-comptable marocain spécialisé dans l'extraction de données de factures.
            Tu connais parfaitement la réglementation fiscale marocaine :
            - Les taux de TVA au Maroc : 0%, 7%, 10%, 14%, 20%
            - Les identifiants fiscaux : ICE (15 chiffres), IF (Identifiant Fiscal), RC (Registre de Commerce), Patente, CNSS
            - Les formats de factures marocaines (arabe et français)
            - Le RIB bancaire marocain (24 chiffres)

            RÈGLES D'EXTRACTION :
            1. Extrais TOUTES les informations présentes, même partielles
            2. L'ICE est un numéro à 15 chiffres, souvent préfixé par "ICE:" ou "I.C.E."
            3. Le montant TTC = montant HT + TVA - remise éventuelle
            4. Si plusieurs taux de TVA, indique le taux principal dans tva_rate et détaille par ligne
            5. Les dates sont souvent au format DD/MM/YYYY — convertis en YYYY-MM-DD
            6. Extrais chaque ligne de la facture avec quantité, prix unitaire, TVA
            7. Si le texte est en arabe, extrais quand même toutes les données
            8. Pour les montants, utilise des nombres décimaux (pas de formatage)

            RÉPONDS UNIQUEMENT avec un objet JSON valide, sans markdown, sans explication.
        """.trimIndent()

        val USER_PROMPT_TEMPLATE = """
            Extrais toutes les données de cette facture marocaine.
            Retourne un JSON avec cette structure exacte :

            {
              "supplier_name": "Raison sociale du fournisseur",
              "supplier_ice": "ICE à 15 chiffres",
              "supplier_if": "Identifiant Fiscal",
              "supplier_rc": "Registre de Commerce",
              "supplier_patente": "Numéro de patente",
              "supplier_cnss": "Numéro CNSS",
              "supplier_address": "Adresse complète",
              "supplier_city": "Ville",
              "client_name": "Nom du client (notre entreprise)",
              "client_ice": "ICE du client",
              "invoice_number": "Numéro de facture",
              "invoice_date": "YYYY-MM-DD",
              "amount_ht": 0.00,
              "tva_rate": 20.00,
              "amount_tva": 0.00,
              "amount_ttc": 0.00,
              "discount_amount": null,
              "discount_percent": null,
              "currency": "MAD",
              "payment_method": "Virement/Chèque/Espèces/Traite/Effet",
              "payment_due_date": "YYYY-MM-DD ou null",
              "bank_name": "Nom de la banque",
              "bank_rib": "RIB 24 chiffres",
              "line_items": [
                {
                  "line_number": 1,
                  "description": "Description du produit/service",
                  "quantity": 1.000,
                  "unit": "Unité/Kg/m²/Heure/Forfait",
                  "unit_price_ht": 0.00,
                  "tva_rate": 20.00,
                  "tva_amount": 0.00,
                  "total_ht": 0.00,
                  "total_ttc": 0.00
                }
              ]
            }

            Utilise null pour les champs non trouvés. Les montants sont des nombres, pas des chaînes.

            TEXTE DE LA FACTURE :
            %s
        """.trimIndent()
    }

    fun extractInvoiceData(rawText: String): ExtractedInvoiceData {
        log.info("Sending text to Claude API for Moroccan invoice extraction ({} chars)", rawText.length)

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
            .block() ?: throw RuntimeException("Empty response from Claude API")

        @Suppress("UNCHECKED_CAST")
        val content = response["content"] as? List<Map<String, Any>>
            ?: throw RuntimeException("Unexpected response format from Claude API")

        val text = content.firstOrNull { it["type"] == "text" }?.get("text") as? String
            ?: throw RuntimeException("No text content in Claude API response")

        // Clean JSON: remove markdown code fences if present
        val cleanJson = text
            .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^```\\s*$", RegexOption.MULTILINE), "")
            .trim()

        log.info("Received structured data from Claude API")
        return objectMapper.readValue(cleanJson, ExtractedInvoiceData::class.java)
    }
}
