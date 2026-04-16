package com.madaef.recondoc.service.extraction

import com.fasterxml.jackson.databind.ObjectMapper
import com.madaef.recondoc.service.AppSettingsService
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
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

    @CircuitBreaker(name = "claude", fallbackMethod = "claudeFallback")
    @RateLimiter(name = "claude")
    @Bulkhead(name = "claude")
    fun callClaude(systemPrompt: String, userContent: String): String {
        if (!isAvailable) throw IllegalStateException("Claude API key not configured. Configure it in Settings > Extraction IA.")

        val model = appSettingsService.getAiModel()
        log.info("Calling Claude API (model={}, text={}chars)", model, userContent.length)

        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to 8192,
            "temperature" to 0,
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
                    val msg = when {
                        body.contains("usage limits") || body.contains("rate_limit") ->
                            "Quota API Anthropic atteint. Reessayez plus tard ou augmentez votre plan."
                        body.contains("authentication") || body.contains("api_key") ->
                            "Cle API Anthropic invalide. Verifiez la configuration dans Parametres > Extraction IA."
                        else -> "Erreur API Claude: $body"
                    }
                    RuntimeException(msg)
                }
            }
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(120))
            .retryWhen(reactor.util.retry.Retry.max(1)
                .filter { it is java.util.concurrent.TimeoutException || it is java.io.IOException })
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

    /**
     * Resilience4j fallback. Signature must match callClaude + one trailing Throwable.
     * We don't silently swallow — we surface a user-friendly error so the extraction
     * pipeline marks the document as ERREUR instead of looping.
     */
    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeFallback(systemPrompt: String, userContent: String, t: Throwable): String {
        val msg = when (t) {
            is CallNotPermittedException ->
                "Service d'extraction IA indisponible (circuit ouvert). Reessayez dans quelques instants."
            is RequestNotPermitted ->
                "Trop d'appels vers l'IA en ce moment (rate limit). Reessayez dans une minute."
            is io.github.resilience4j.bulkhead.BulkheadFullException ->
                "Trop d'extractions en parallele. Reessayez dans quelques secondes."
            else -> t.message ?: "Erreur inattendue lors de l'appel a l'IA"
        }
        log.warn("Claude call short-circuited: {} ({})", msg, t.javaClass.simpleName)
        throw RuntimeException(msg, t)
    }
}
