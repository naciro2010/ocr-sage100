package com.madaef.recondoc.service.extraction

import com.fasterxml.jackson.databind.ObjectMapper
import com.madaef.recondoc.entity.ClaudeUsage
import com.madaef.recondoc.repository.ClaudeUsageRepository
import com.madaef.recondoc.service.AppSettingsService
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CallKind(val key: String) {
    CLASSIFICATION("classification"),
    EXTRACTION("extraction"),
    RULES_BATCH("rules_batch")
}

/**
 * Reponse complete de Claude. Expose stop_reason pour que les callers
 * puissent detecter une reponse tronquee (max_tokens atteint) et basculer
 * le document en revue humaine plutot que de consommer un JSON incomplet.
 */
data class ClaudeResponse(
    val text: String,
    val stopReason: String?
)

@Service
class LlmExtractionService(
    private val objectMapper: ObjectMapper,
    private val appSettingsService: AppSettingsService,
    private val claudeUsageRepository: ClaudeUsageRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val clientCache = ConcurrentHashMap<String, WebClient>()

    val isAvailable: Boolean get() = appSettingsService.getAiApiKey().isNotBlank()

    private fun modelFor(kind: CallKind): String = when (kind) {
        CallKind.CLASSIFICATION -> appSettingsService.getAiClassificationModel()
        CallKind.EXTRACTION -> appSettingsService.getAiExtractionModel()
        CallKind.RULES_BATCH -> appSettingsService.getAiRulesBatchModel()
    }

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
    fun callClaude(systemPrompt: String, userContent: String): String =
        executeClaudeCall(systemPrompt, userContent, CallKind.EXTRACTION).text

    @CircuitBreaker(name = "claude", fallbackMethod = "claudeFallbackKind")
    @RateLimiter(name = "claude")
    @Bulkhead(name = "claude")
    fun callClaude(systemPrompt: String, userContent: String, kind: CallKind): String =
        executeClaudeCall(systemPrompt, userContent, kind).text

    /**
     * Variante qui expose la reponse complete (texte + stop_reason). A utiliser
     * cote extraction quand il faut savoir si la reponse a ete tronquee par
     * max_tokens — dans ce cas le JSON est incomplet et le document doit
     * partir en revue humaine.
     */
    @CircuitBreaker(name = "claude", fallbackMethod = "claudeFullFallback")
    @RateLimiter(name = "claude")
    @Bulkhead(name = "claude")
    fun callClaudeFull(systemPrompt: String, userContent: String, kind: CallKind): ClaudeResponse =
        executeClaudeCall(systemPrompt, userContent, kind)

    private fun executeClaudeCall(systemPrompt: String, userContent: String, kind: CallKind): ClaudeResponse {
        if (!isAvailable) throw IllegalStateException("Claude API key not configured. Configure it in Settings > Extraction IA.")

        val model = modelFor(kind)
        val maxTokens = appSettingsService.getAiMaxTokens(kind.key)
        val started = System.currentTimeMillis()
        log.info("Calling Claude API (kind={}, model={}, max_tokens={}, text={}chars)",
            kind, model, maxTokens, userContent.length)

        // temperature=0 : extraction structuree, reponse deterministe.
        // Evite que deux appels identiques produisent des valeurs differentes
        // et stabilise les tests golden.
        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to maxTokens,
            "temperature" to 0,
            "system" to systemPrompt,
            "messages" to listOf(mapOf("role" to "user", "content" to userContent))
        )

        val response = try {
            getClient().post()
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
                .retryWhen(
                    reactor.util.retry.Retry
                        .backoff(1, Duration.ofMillis(500))
                        .jitter(0.5)
                        .filter { it is java.util.concurrent.TimeoutException || it is java.io.IOException }
                )
                .block() ?: throw RuntimeException("Empty response from Claude API")
        } catch (e: Exception) {
            recordUsage(model, null, started, false, e.message)
            throw e
        }

        val usage = response["usage"] as? Map<*, *>
        recordUsage(model, usage, started, true, null)

        @Suppress("UNCHECKED_CAST")
        val content = response["content"] as? List<Map<String, Any>>
            ?: throw RuntimeException("Unexpected response format")

        val text = content.firstOrNull { it["type"] == "text" }?.get("text") as? String
            ?: throw RuntimeException("No text content in response")

        val stopReason = response["stop_reason"] as? String
        if (stopReason != null && stopReason != "end_turn" && stopReason != "stop_sequence") {
            log.warn("Claude call (kind={}) ended with stop_reason={} — output may be truncated or partial", kind, stopReason)
        }

        val cleanedText = text
            .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^```\\s*$", RegexOption.MULTILINE), "")
            .trim()

        return ClaudeResponse(text = cleanedText, stopReason = stopReason)
    }

    /**
     * Persist a row capturing the cost of this call. dossierId / documentId are
     * read from MDC (set by DossierService) so the API stays stateless and we
     * don't have to thread context through every caller.
     */
    private fun recordUsage(model: String, usage: Map<*, *>?, started: Long, success: Boolean, error: String?) {
        try {
            val row = ClaudeUsage(
                dossierId = MDC.get("dossierId")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                documentId = MDC.get("documentId")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                model = model,
                inputTokens = (usage?.get("input_tokens") as? Number)?.toInt() ?: 0,
                outputTokens = (usage?.get("output_tokens") as? Number)?.toInt() ?: 0,
                durationMs = System.currentTimeMillis() - started,
                success = success,
                error = error?.take(2000)
            )
            claudeUsageRepository.save(row)
        } catch (e: Exception) {
            // Tracking must never break the extraction pipeline.
            log.warn("Failed to record Claude usage: {}", e.message)
        }
    }

    fun <T> extractStructured(systemPrompt: String, rawText: String, clazz: Class<T>): T {
        val json = callClaude(systemPrompt, rawText, CallKind.EXTRACTION)
        log.debug("LLM response: {}", json.take(500))
        return objectMapper.readValue(json, clazz)
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeFallback(systemPrompt: String, userContent: String, t: Throwable): String =
        fallbackMessage(t)

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeFallbackKind(systemPrompt: String, userContent: String, kind: CallKind, t: Throwable): String =
        fallbackMessage(t)

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeFullFallback(systemPrompt: String, userContent: String, kind: CallKind, t: Throwable): ClaudeResponse {
        fallbackMessage(t) // always throws
        throw IllegalStateException("unreachable")
    }

    private fun fallbackMessage(t: Throwable): String {
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
