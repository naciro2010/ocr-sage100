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

/**
 * Reponse d'un appel Claude en mode `tool_use` : Claude a ete force a
 * appeler l'outil decrit par le schema JSON, donc `toolInput` est un
 * objet structure directement exploitable (plus de parse regex).
 * `stopReason` est typiquement "tool_use" en cas de succes ; "max_tokens"
 * signale une reponse tronquee -> document en revue humaine.
 */
data class ClaudeToolResponse(
    val toolInput: Map<String, Any?>,
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

    companion object {
        private val CLAUDE_CALL_TIMEOUT = Duration.ofSeconds(120)
        private val CLAUDE_RETRY_SPEC = reactor.util.retry.Retry
            .backoff(1, Duration.ofMillis(500))
            .jitter(0.5)
            .filter { it is java.util.concurrent.TimeoutException || it is java.io.IOException }
    }

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

    /**
     * Appel Claude en mode `tool_use` avec schema JSON force. Claude ne peut
     * pas repondre en texte libre — il est oblige d'appeler l'outil nomme
     * `toolName` avec un input conforme a `inputSchema`. Garantit un objet
     * structure en retour, sans parse regex ni JSON tronque parsable.
     */
    @CircuitBreaker(name = "claude", fallbackMethod = "claudeToolFallback")
    @RateLimiter(name = "claude")
    @Bulkhead(name = "claude")
    fun callClaudeTool(
        systemPrompt: String,
        userContent: String,
        toolName: String,
        inputSchema: Map<String, Any>,
        kind: CallKind
    ): ClaudeToolResponse = executeClaudeToolCall(systemPrompt, userContent, toolName, inputSchema, kind)

    private fun executeClaudeToolCall(
        systemPrompt: String,
        userContent: String,
        toolName: String,
        inputSchema: Map<String, Any>,
        kind: CallKind
    ): ClaudeToolResponse {
        val model = modelFor(kind)
        val maxTokens = appSettingsService.getAiMaxTokens(kind.key)
        log.info("Calling Claude API (tool_use kind={}, model={}, tool={}, max_tokens={}, text={}chars)",
            kind, model, toolName, maxTokens, userContent.length)

        // Prompt caching : on marque la definition de l'outil comme cache
        // ephemere (5 min TTL cote Anthropic). Le schema JSON est identique
        // pour tous les documents d'un meme type (ex: FACTURE), donc chaque
        // appel suivant lit le prefixe depuis le cache -> ~90% moins cher.
        val tool = mapOf(
            "name" to toolName,
            "description" to "Structured extraction tool. Call this exactly once with the extracted data.",
            "input_schema" to inputSchema,
            "cache_control" to mapOf("type" to "ephemeral")
        )

        // Pas de `temperature` ici non plus (cf. PR #78). `tool_choice` force
        // deja Claude a produire un objet conforme au schema, ce qui rend le
        // parametre temperature redondant pour l'extraction structuree.
        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to maxTokens,
            "system" to cacheableSystem(systemPrompt),
            "tools" to listOf(tool),
            "tool_choice" to mapOf("type" to "tool", "name" to toolName),
            "messages" to listOf(mapOf("role" to "user", "content" to userContent))
        )

        val response = postToAnthropic(requestBody, model, " (tool_use)")

        @Suppress("UNCHECKED_CAST")
        val content = response["content"] as? List<Map<String, Any>>
            ?: throw RuntimeException("Unexpected response format (tool_use)")

        val toolUseBlock = content.firstOrNull { it["type"] == "tool_use" }
            ?: throw RuntimeException("No tool_use block in response. stop_reason=${response["stop_reason"]}")

        @Suppress("UNCHECKED_CAST")
        val input = toolUseBlock["input"] as? Map<String, Any?>
            ?: throw RuntimeException("Empty tool_use input")

        val stopReason = response["stop_reason"] as? String
        if (stopReason == "max_tokens") {
            log.warn("Claude tool_use call (kind={}) hit max_tokens — input may be partial", kind)
        }

        return ClaudeToolResponse(toolInput = input, stopReason = stopReason)
    }

    private fun executeClaudeCall(systemPrompt: String, userContent: String, kind: CallKind): ClaudeResponse {
        val model = modelFor(kind)
        val maxTokens = appSettingsService.getAiMaxTokens(kind.key)
        log.info("Calling Claude API (kind={}, model={}, max_tokens={}, text={}chars)",
            kind, model, maxTokens, userContent.length)

        // Note: le parametre `temperature` a ete retire (cf. PR #78). Les
        // modeles Claude recents (Haiku 4.5+, Sonnet 4.6+, Opus 4.x) l'ont
        // deprecie au profit d'un comportement deterministe par defaut sur
        // les appels structures, et renvoient 400 si on le fournit.
        val requestBody = mapOf(
            "model" to model,
            "max_tokens" to maxTokens,
            "system" to cacheableSystem(systemPrompt),
            "messages" to listOf(mapOf("role" to "user", "content" to userContent))
        )

        val response = postToAnthropic(requestBody, model, "")

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
     * Enveloppe le prompt systeme en content-block unique pour activer le
     * prompt caching Anthropic (ephemere, TTL 5 min). Notre prompt systeme
     * ne change pas entre deux documents du meme type : premiere requete
     * facture 100% du prix (creation cache), les suivantes ~10% du prix sur
     * le prefixe cache. Anthropic ignore silencieusement `cache_control` si
     * le bloc est plus court que son minimum (1024 tokens Sonnet, 2048 Haiku),
     * donc aucun risque de regression sur les prompts courts.
     */
    private fun cacheableSystem(systemPrompt: String): List<Map<String, Any>> = listOf(
        mapOf(
            "type" to "text",
            "text" to systemPrompt,
            "cache_control" to mapOf("type" to "ephemeral")
        )
    )

    /**
     * Envoi un payload a /v1/messages et recupere la reponse parsee. Factorise
     * auth check, timeout, retry sur TimeoutException/IOException, mapping des
     * erreurs 4xx, et enregistrement de la telemetrie ClaudeUsage (succes/echec).
     * Le parse du contenu (text vs tool_use) reste la responsabilite du caller.
     */
    private fun postToAnthropic(
        requestBody: Map<String, Any>,
        model: String,
        errorTag: String
    ): Map<*, *> {
        if (!isAvailable) throw IllegalStateException("Claude API key not configured. Configure it in Settings > Extraction IA.")
        val started = System.currentTimeMillis()
        val response = try {
            getClient().post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus({ it.is4xxClientError }) { resp ->
                    resp.bodyToMono(String::class.java).map { body ->
                        log.error("Claude API 4xx error{}: {} — {}", errorTag, resp.statusCode(), body)
                        val msg = when {
                            body.contains("usage limits") || body.contains("rate_limit") ->
                                "Quota API Anthropic atteint. Reessayez plus tard ou augmentez votre plan."
                            body.contains("authentication") || body.contains("api_key") ->
                                "Cle API Anthropic invalide. Verifiez la configuration dans Parametres > Extraction IA."
                            else -> "Erreur API Claude$errorTag: $body"
                        }
                        RuntimeException(msg)
                    }
                }
                .bodyToMono(Map::class.java)
                .timeout(CLAUDE_CALL_TIMEOUT)
                .retryWhen(CLAUDE_RETRY_SPEC)
                .block() ?: throw RuntimeException("Empty response from Claude API")
        } catch (e: Exception) {
            recordUsage(model, null, started, false, e.message)
            throw e
        }
        val usage = response["usage"] as? Map<*, *>
        recordUsage(model, usage, started, true, null)
        return response
    }

    /**
     * Persist a row capturing the cost of this call. dossierId / documentId are
     * read from MDC (set by DossierService) so the API stays stateless and we
     * don't have to thread context through every caller.
     */
    private fun recordUsage(model: String, usage: Map<*, *>?, started: Long, success: Boolean, error: String?) {
        try {
            val cacheCreation = (usage?.get("cache_creation_input_tokens") as? Number)?.toInt() ?: 0
            val cacheRead = (usage?.get("cache_read_input_tokens") as? Number)?.toInt() ?: 0
            val row = ClaudeUsage(
                dossierId = MDC.get("dossierId")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                documentId = MDC.get("documentId")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                model = model,
                inputTokens = (usage?.get("input_tokens") as? Number)?.toInt() ?: 0,
                outputTokens = (usage?.get("output_tokens") as? Number)?.toInt() ?: 0,
                cacheCreationInputTokens = cacheCreation,
                cacheReadInputTokens = cacheRead,
                durationMs = System.currentTimeMillis() - started,
                success = success,
                error = error?.take(2000)
            )
            claudeUsageRepository.save(row)
            if (cacheRead > 0) {
                log.debug("Prompt cache HIT model={} read={}tok creation={}tok", model, cacheRead, cacheCreation)
            }
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

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeToolFallback(
        systemPrompt: String, userContent: String, toolName: String,
        inputSchema: Map<String, Any>, kind: CallKind, t: Throwable
    ): ClaudeToolResponse {
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
