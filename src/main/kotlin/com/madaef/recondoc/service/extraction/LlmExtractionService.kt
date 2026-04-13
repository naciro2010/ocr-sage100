package com.madaef.recondoc.service.extraction

import com.fasterxml.jackson.databind.ObjectMapper
import com.madaef.recondoc.service.AppSettingsService
import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Service
class LlmExtractionService(
    private val objectMapper: ObjectMapper,
    private val appSettingsService: AppSettingsService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val clientCache = ConcurrentHashMap<String, WebClient>()

    val isAvailable: Boolean get() = appSettingsService.getAiApiKey().isNotBlank()

    private fun getClient(): WebClient {
        val apiKey = appSettingsService.getAiApiKey()
        val baseUrl = appSettingsService.getAiBaseUrl()
        val cacheKey = "$baseUrl|${apiKey.takeLast(8)}"
        return clientCache.computeIfAbsent(cacheKey) {
            val httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(120))
            WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
                .build()
        }
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

        val response = getClient().post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .onStatus({ it.is4xxClientError }) { resp ->
                resp.bodyToMono(String::class.java).map { body ->
                    log.error("Claude API 4xx error: {} — {}", resp.statusCode(), body)
                    RuntimeException("Claude API error ${resp.statusCode()}: $body")
                }
            }
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(120))
            .retryWhen(reactor.util.retry.Retry.max(1)
                .filter { it !is org.springframework.web.reactive.function.client.WebClientResponseException.BadRequest })
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
