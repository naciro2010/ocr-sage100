package com.ocrsage.service.extraction

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class LlmExtractionService(
    private val objectMapper: ObjectMapper,
    @Value("\${claude.api-key:}") private val apiKey: String,
    @Value("\${claude.model:claude-sonnet-4-6}") private val model: String,
    @Value("\${claude.base-url:https://api.anthropic.com}") private val baseUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val isAvailable: Boolean get() = apiKey.isNotBlank()

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("x-api-key", apiKey)
            .defaultHeader("anthropic-version", "2023-06-01")
            .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
            .build()
    }

    fun callClaude(systemPrompt: String, userContent: String): String {
        if (!isAvailable) throw IllegalStateException("Claude API key not configured")

        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to 8192,
            "system" to systemPrompt,
            "messages" to listOf(mapOf("role" to "user", "content" to userContent))
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
