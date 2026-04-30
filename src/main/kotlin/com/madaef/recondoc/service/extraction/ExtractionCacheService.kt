package com.madaef.recondoc.service.extraction

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Cache deterministe in-memory des reponses Claude `tool_use` (Sprint 2 #6
 * fiabilite). Resout deux problemes :
 *
 *  1. **Non-determinisme residuel** : meme avec `temperature=0`, Claude peut
 *     produire deux reponses differentes pour deux appels strictement
 *     identiques (jitter interne, mises a jour silencieuses du modele).
 *     Le cache fige le resultat sur la duree du TTL : 1ere extraction du
 *     meme document = un seul appel, 2 verdicts identiques.
 *
 *  2. **Re-uploads gratuits** : un document re-uploade dans le meme dossier
 *     ou un autre (RIB partage, attestation reutilisee...) ne re-paye pas
 *     l'extraction Claude tant que la cle de cache reste valide.
 *
 * Le cache d'Anthropic (prompt caching TTL 1h) ne couvre QUE les blocs
 * marques `cache_control` (system prefix, schema), pas le user content
 * lui-meme. Sur deux uploads du meme document, Anthropic re-facture les
 * tokens user content et regenere une reponse. Ce cache local elimine
 * cet appel residuel.
 *
 * Cle de cache (SHA-256) :
 *  - model (sonnet-4.6, haiku-4.5...)
 *  - toolName
 *  - systemPrompt complet (apres pseudonymisation)
 *  - userContent complet (texte OCR pseudonymise)
 *  - inputSchema serialise
 *  - temperature
 * -> deux appels sur le meme document avec le meme prompt et schema =
 * meme cle = meme reponse.
 *
 * Borne (defaut 1024 entries, eviction LRU) pour eviter la croissance
 * non-bornee dans une JVM long-runnante. TTL 1h aligne sur la duree
 * du prompt cache Anthropic.
 *
 * Inerte si `ai.extraction-cache.enabled=false` ou si la cle ne peut
 * pas etre calculee (failure SHA-256 -> miss + log).
 */
@Service
class ExtractionCacheService(
    @Value("\${ai.extraction-cache.enabled:true}") private val enabled: Boolean,
    @Value("\${ai.extraction-cache.max-entries:1024}") private val maxEntries: Int,
    @Value("\${ai.extraction-cache.ttl-seconds:3600}") private val ttlSeconds: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Entry(
        val toolInput: Map<String, Any?>,
        val stopReason: String?,
        val storedAt: Instant
    )

    /**
     * LinkedHashMap accessOrder=true => LRU. Synchronise via
     * Collections.synchronizedMap pour garantir la lecture/ecriture
     * thread-safe dans le pipeline d'extraction asynchrone (Reactor +
     * Bulkhead).
     */
    private val cache: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Entry>): Boolean =
                size > maxEntries
        }
    )

    fun isEnabled(): Boolean = enabled

    /**
     * Calcule la cle SHA-256 deterministe pour un appel Claude tool_use.
     * Tous les composants sont concatenes avec un separateur explicite
     * (``) pour eviter les collisions par recombinaison.
     */
    fun keyFor(
        model: String,
        toolName: String,
        systemPrompt: String,
        userContent: String,
        inputSchemaJson: String,
        temperature: Double?
    ): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val parts = listOf(
                model,
                toolName,
                systemPrompt,
                userContent,
                inputSchemaJson,
                temperature?.toString().orEmpty()
            )
            for (p in parts) {
                md.update(p.toByteArray(Charsets.UTF_8))
                md.update(0x01) // separateur null pour eviter les collisions
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            log.warn("Cache key computation failed: {}", e.message)
            null
        }
    }

    /**
     * Cherche une reponse cachee. Retourne null si absent ou expire (TTL
     * depasse). Sur expiration, l'entree est silencieusement evictee a
     * la prochaine ecriture (eviction lazy, pas de scheduler).
     */
    fun lookup(key: String): CachedToolResponse? {
        if (!enabled) return null
        val entry = cache[key] ?: return null
        val ageSec = Instant.now().epochSecond - entry.storedAt.epochSecond
        if (ageSec > ttlSeconds) {
            cache.remove(key)
            return null
        }
        log.debug("ExtractionCache HIT (age={}s)", ageSec)
        return CachedToolResponse(entry.toolInput, entry.stopReason)
    }

    fun store(key: String, toolInput: Map<String, Any?>, stopReason: String?) {
        if (!enabled) return
        // Ne pas cacher les reponses tronquees : elles indiquent un budget
        // tokens insuffisant et le caller va relancer avec max_tokens
        // augmente. Cacher la reponse partielle figerait l'erreur.
        if (stopReason == "max_tokens") {
            log.debug("ExtractionCache skip store (stop_reason=max_tokens)")
            return
        }
        cache[key] = Entry(toolInput, stopReason, Instant.now())
    }

    /**
     * Reponse cachee identique a `ClaudeToolResponse` mais decouplee pour
     * eviter une dependance circulaire avec LlmExtractionService.
     */
    data class CachedToolResponse(
        val toolInput: Map<String, Any?>,
        val stopReason: String?
    )

    /** Pour les tests et le diagnostic ops. */
    fun size(): Int = cache.size

    /** Vide le cache (admin / test). */
    fun clear() {
        cache.clear()
    }
}
