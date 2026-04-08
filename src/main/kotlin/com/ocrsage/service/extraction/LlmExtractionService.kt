package com.ocrsage.service.extraction

import com.fasterxml.jackson.databind.ObjectMapper
import com.ocrsage.service.AppSettingsService
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

/**
 * LLM extraction service that reads API key, model, and base URL dynamically
 * from AppSettingsService (configurable via Settings UI).
 * Supports multi-model: Claude Sonnet, Opus, Haiku.
 */
@Service
class LlmExtractionService(
    private val objectMapper: ObjectMapper,
    private val appSettingsService: AppSettingsService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val isAvailable: Boolean get() = appSettingsService.getAiApiKey().isNotBlank()

    private fun buildClient(): WebClient {
        val apiKey = appSettingsService.getAiApiKey()
        val baseUrl = appSettingsService.getAiBaseUrl()
        return WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("x-api-key", apiKey)
            .defaultHeader("anthropic-version", "2023-06-01")
            .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
            .build()
    }

    fun callClaude(systemPrompt: String, userContent: String): String {
        if (!isAvailable) throw IllegalStateException("Claude API key not configured. Configure it in Settings > Extraction IA.")

        val model = appSettingsService.getAiModel()
        log.info("Calling Claude API (model={}, text={}chars)", model, userContent.length)

        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to 8192,
            "system" to systemPrompt,
            "messages" to listOf(mapOf("role" to "user", "content" to userContent))
        )

        val response = buildClient().post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Map::class.java)
            .block() ?: throw RuntimeException("Empty response from Claude API")

        @Suppress("UNCHECKED_CAST")
        val content = response["content"] as? List<Map<String, Any>>
            ?: throw RuntimeException("Unexpected response format")

        val text = content.firstOrNull { it["type"] == "text" }?.get("text") as? String
            ?: throw RuntimeException("No text content in response")

        return text
            .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^```\\s*$", RegexOption.MULTILINE), "")
            .trim()
    }

    fun <T> extractStructured(systemPrompt: String, rawText: String, clazz: Class<T>): T {
        val json = callClaude(systemPrompt, rawText)
        log.debug("LLM response: {}", json.take(500))
        return objectMapper.readValue(json, clazz)
    }
}
