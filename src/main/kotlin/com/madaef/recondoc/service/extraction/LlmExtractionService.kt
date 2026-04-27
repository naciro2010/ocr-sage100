package com.madaef.recondoc.service.extraction

import tools.jackson.databind.ObjectMapper
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
                // extended-cache-ttl active la valeur ttl="1h" sur cache_control
                // (defaut Anthropic = 5min). Permet aux prefixes stables (regles
                // communes Maroc, descriptions de tools) de survivre aux bursts
                // d'upload nocturnes et aux pauses entre dossiers d'une meme
                // session, divisant le cout prompt sur les fenetres > 5 min.
                .defaultHeader("anthropic-beta", "extended-cache-ttl-2025-04-11")
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
    ): ClaudeToolResponse = executeClaudeToolCall(null, systemPrompt, userContent, toolName, inputSchema, kind, null)

    /**
     * Variante a deux blocs cacheables pour le system prompt :
     *  - `stableSystemPrefix` (ex: COMMON_RULES extraction) est cache TTL 1h
     *    et partage entre TOUS les types de documents.
     *  - `systemPrompt` (specifique au type, few-shots, schema) est cache 5 min.
     * Sur un upload de 5 dossiers de types differents, le bloc stable est
     * paye une seule fois (creation cache) puis lu pour tous les suivants
     * (~10% du prix tokens). Reduit le cout prompt cross-type de 60-80%
     * vs un seul bloc cache par type.
     */
    @CircuitBreaker(name = "claude", fallbackMethod = "claudeToolCachedFallback")
    @RateLimiter(name = "claude")
    @Bulkhead(name = "claude")
    fun callClaudeToolCached(
        stableSystemPrefix: String?,
        systemPrompt: String,
        userContent: String,
        toolName: String,
        inputSchema: Map<String, Any>,
        kind: CallKind
    ): ClaudeToolResponse = executeClaudeToolCall(stableSystemPrefix, systemPrompt, userContent, toolName, inputSchema, kind, null)

    /**
     * Variante qui force un `max_tokens` specifique (override le setting).
     * Utile apres un premier appel tronque (`stop_reason=max_tokens`) : on
     * retente avec un budget plus large AVANT de basculer le document en
     * revue humaine. Preserve la fiabilite sur les gros documents (OP avec
     * beaucoup de retenues, contrats cadres avec grilles tarifaires...)
     * sans surcout permanent.
     */
    @CircuitBreaker(name = "claude", fallbackMethod = "claudeToolMaxTokensFallback")
    @RateLimiter(name = "claude")
    @Bulkhead(name = "claude")
    fun callClaudeToolWithMaxTokens(
        systemPrompt: String,
        userContent: String,
        toolName: String,
        inputSchema: Map<String, Any>,
        kind: CallKind,
        maxTokensOverride: Int
    ): ClaudeToolResponse = executeClaudeToolCall(null, systemPrompt, userContent, toolName, inputSchema, kind, maxTokensOverride)

    /**
     * Variante combinant cache prefixe stable + max_tokens override pour le
     * retry sur reponse tronquee : conserve la cle de cache TTL 1h pour
     * eviter de payer a nouveau les regles communes a chaque retry.
     */
    @CircuitBreaker(name = "claude", fallbackMethod = "claudeToolCachedMaxTokensFallback")
    @RateLimiter(name = "claude")
    @Bulkhead(name = "claude")
    fun callClaudeToolCachedWithMaxTokens(
        stableSystemPrefix: String?,
        systemPrompt: String,
        userContent: String,
        toolName: String,
        inputSchema: Map<String, Any>,
        kind: CallKind,
        maxTokensOverride: Int
    ): ClaudeToolResponse = executeClaudeToolCall(stableSystemPrefix, systemPrompt, userContent, toolName, inputSchema, kind, maxTokensOverride, null)

    /**
     * Variante avec override de temperature (utilisee par la self-consistency
     * sur identifiants critiques). Le second run utilise une temperature > 0
     * pour briser le determinisme local et exposer les hallucinations stables
     * que le run a temperature 0 reproduirait a l'identique.
     */
    @CircuitBreaker(name = "claude", fallbackMethod = "claudeToolTemperatureFallback")
    @RateLimiter(name = "claude")
    @Bulkhead(name = "claude")
    fun callClaudeToolWithTemperature(
        systemPrompt: String,
        userContent: String,
        toolName: String,
        inputSchema: Map<String, Any>,
        kind: CallKind,
        temperatureOverride: Double
    ): ClaudeToolResponse = executeClaudeToolCall(null, systemPrompt, userContent, toolName, inputSchema, kind, null, temperatureOverride)

    /**
     * Variante combinant le cache prefixe stable + un override de temperature.
     * Utilisee pour les RE-extractions (retry low-confidence, retry quality
     * score) : on conserve le cache TTL 1h des few-shots / schema (sinon on
     * pulverise le cache cross-document), tout en permettant a Claude
     * d'explorer une lecture differente. Temperature 0 sur le retry est
     * inutile car Claude reproduirait a l'identique le premier appel.
     */
    @CircuitBreaker(name = "claude", fallbackMethod = "claudeToolCachedTemperatureFallback")
    @RateLimiter(name = "claude")
    @Bulkhead(name = "claude")
    fun callClaudeToolCachedWithTemperature(
        stableSystemPrefix: String?,
        systemPrompt: String,
        userContent: String,
        toolName: String,
        inputSchema: Map<String, Any>,
        kind: CallKind,
        temperatureOverride: Double
    ): ClaudeToolResponse = executeClaudeToolCall(stableSystemPrefix, systemPrompt, userContent, toolName, inputSchema, kind, null, temperatureOverride)

    private fun executeClaudeToolCall(
        stableSystemPrefix: String?,
        systemPrompt: String,
        userContent: String,
        toolName: String,
        inputSchema: Map<String, Any>,
        kind: CallKind,
        maxTokensOverride: Int?,
        temperatureOverride: Double? = null
    ): ClaudeToolResponse {
        val model = modelFor(kind)
        val maxTokens = maxTokensOverride ?: appSettingsService.getAiMaxTokens(kind.key)
        log.info("Calling Claude API (tool_use kind={}, model={}, tool={}, max_tokens={}, text={}chars)",
            kind, model, toolName, maxTokens, userContent.length)

        // Prompt caching : on marque la definition de l'outil comme cache
        // ephemere (TTL 1h via beta header extended-cache-ttl). Le schema JSON
        // est identique pour tous les documents d'un meme type (ex: FACTURE),
        // donc chaque appel suivant lit le prefixe depuis le cache -> ~10% du
        // prix sur le bloc cache. TTL 1h vs 5min permet aux few-shots et
        // schemas de survivre aux pauses entre dossiers.
        val tool = mapOf(
            "name" to toolName,
            "description" to "Structured extraction tool. Call this exactly once with the extracted data.",
            "input_schema" to inputSchema,
            "cache_control" to mapOf("type" to "ephemeral", "ttl" to "1h")
        )

        // Temperature 0 par defaut (opt-out via `ai.temperature = -1` si un modele
        // renvoie 400). Deterministe : deux runs du meme document produisent la
        // meme extraction. `tool_choice` contraint deja le schema, mais Claude
        // garde des choix libres sur les valeurs (numero facture, montants...) —
        // c'est la que temperature 0 evite les derives d'une run a l'autre.
        // disable_parallel_tool_use = true : on s'attend a UN SEUL appel d'outil
        // (extraction d'un document) ; Claude n'a aucun benefice a multiplier
        // les appels et le faire augmente le risque de tronquage et de cle
        // doublonnee dans la response.
        val baseBody = mapOf(
            "model" to model,
            "max_tokens" to maxTokens,
            "system" to cacheableSystem(stableSystemPrefix, systemPrompt),
            "tools" to listOf(tool),
            "tool_choice" to mapOf(
                "type" to "tool",
                "name" to toolName,
                "disable_parallel_tool_use" to true
            ),
            "messages" to listOf(mapOf("role" to "user", "content" to userContent))
        )
        // temperatureOverride bypass le setting global pour le second run de
        // self-consistency (cf. IdentifierConsistencyService) sans changer la
        // configuration applicative.
        val requestBody = if (temperatureOverride != null && temperatureOverride >= 0.0) {
            baseBody + ("temperature" to temperatureOverride)
        } else {
            withTemperature(baseBody)
        }

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

        // Temperature 0 par defaut (cf. withTemperature). Si un modele specifique
        // rejette le parametre en 400, positionner `ai.temperature = -1` dans les
        // settings pour ne pas l'envoyer.
        val requestBody = withTemperature(
            mapOf(
                "model" to model,
                "max_tokens" to maxTokens,
                "system" to cacheableSystem(null, systemPrompt),
                "messages" to listOf(mapOf("role" to "user", "content" to userContent))
            )
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
     * Ajoute `temperature` au body si le setting est >= 0 (defaut 0.0). La
     * fiabilite 100% exige du determinisme : deux runs identiques doivent
     * produire le meme JSON. Opt-out : `ai.temperature = -1` si un modele
     * renvoie un 400 sur ce parametre.
     */
    private fun withTemperature(body: Map<String, Any>): Map<String, Any> {
        val t = appSettingsService.getAiTemperature()
        return if (t >= 0.0) body + ("temperature" to t) else body
    }

    /**
     * Enveloppe le prompt systeme en 1 ou 2 content-blocks pour activer le
     * prompt caching Anthropic.
     *
     *  - 1 bloc (mode legacy, stableSystemPrefix=null) : tout le system est
     *    cache TTL 1h via beta `extended-cache-ttl`. Reduction ~90% du cout
     *    prompt sur les appels successifs du meme type de document.
     *
     *  - 2 blocs (mode cross-type) :
     *      * stableSystemPrefix : regles communes Maroc + format JSON,
     *        identiques pour tous les types -> cache 1h, hit cross-type.
     *      * systemPrompt : portion specifique au type (few-shots, schema,
     *        regles dediees) -> cache 1h aussi, hit sur meme type.
     *    Les deux blocs etant marques cache_control, Anthropic stocke deux
     *    breakpoints distincts ; un upload de 5 dossiers de types varies
     *    paie le bloc commun UNE fois et le bloc specifique par type.
     *
     * Anthropic ignore silencieusement `cache_control` si le bloc est plus
     * court que son minimum (1024 tokens Sonnet, 2048 Haiku), donc aucun
     * risque de regression sur les prompts courts.
     */
    private fun cacheableSystem(
        stableSystemPrefix: String?,
        systemPrompt: String
    ): List<Map<String, Any>> {
        val blocks = mutableListOf<Map<String, Any>>()
        if (!stableSystemPrefix.isNullOrBlank()) {
            blocks += mapOf(
                "type" to "text",
                "text" to stableSystemPrefix,
                "cache_control" to mapOf("type" to "ephemeral", "ttl" to "1h")
            )
        }
        blocks += mapOf(
            "type" to "text",
            "text" to systemPrompt,
            "cache_control" to mapOf("type" to "ephemeral", "ttl" to "1h")
        )
        return blocks
    }

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

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeToolCachedFallback(
        stableSystemPrefix: String?, systemPrompt: String, userContent: String,
        toolName: String, inputSchema: Map<String, Any>, kind: CallKind, t: Throwable
    ): ClaudeToolResponse {
        fallbackMessage(t)
        throw IllegalStateException("unreachable")
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeToolMaxTokensFallback(
        systemPrompt: String, userContent: String, toolName: String,
        inputSchema: Map<String, Any>, kind: CallKind, maxTokensOverride: Int, t: Throwable
    ): ClaudeToolResponse {
        fallbackMessage(t) // always throws
        throw IllegalStateException("unreachable")
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeToolCachedMaxTokensFallback(
        stableSystemPrefix: String?, systemPrompt: String, userContent: String,
        toolName: String, inputSchema: Map<String, Any>, kind: CallKind,
        maxTokensOverride: Int, t: Throwable
    ): ClaudeToolResponse {
        fallbackMessage(t)
        throw IllegalStateException("unreachable")
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeToolTemperatureFallback(
        systemPrompt: String, userContent: String, toolName: String,
        inputSchema: Map<String, Any>, kind: CallKind,
        temperatureOverride: Double, t: Throwable
    ): ClaudeToolResponse {
        fallbackMessage(t)
        throw IllegalStateException("unreachable")
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun claudeToolCachedTemperatureFallback(
        stableSystemPrefix: String?, systemPrompt: String, userContent: String,
        toolName: String, inputSchema: Map<String, Any>, kind: CallKind,
        temperatureOverride: Double, t: Throwable
    ): ClaudeToolResponse {
        fallbackMessage(t)
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
