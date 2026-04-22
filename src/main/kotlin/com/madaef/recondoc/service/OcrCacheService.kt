package com.madaef.recondoc.service

import com.madaef.recondoc.entity.OcrCacheEntry
import com.madaef.recondoc.repository.OcrCacheRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * Cache cross-dossier du resultat OCR par SHA-256 du fichier source. Cible
 * les retraitements (`processDocument` sans changement de fichier) et les
 * documents communs partages entre dossiers (RIB, attestations, gabarits).
 *
 * Cas non couverts volontairement :
 *  - fichier recu sans Path local : le cache n'est lu que lorsque le service
 *    OCR a un Path (on ne hash pas l'InputStream pour eviter de casser le
 *    contrat de consommation sequentiel de Tika).
 *  - moteurs qui evoluent (prompts, regex, scoring) : bumper `ocr.cache.version`
 *    pour invalider l'ensemble sans DROP TABLE.
 */
@Service
class OcrCacheService(
    private val repo: OcrCacheRepository,
    @Value("\${ocr.cache.enabled:true}") private val enabled: Boolean,
    @Value("\${ocr.cache.version:v1}") private val cacheVersion: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class CachedOcr(
        val text: String,
        val engine: String,
        val pageCount: Int,
        val confidence: Double
    )

    fun isEnabled(): Boolean = enabled

    /**
     * Hash SHA-256 streaming (pas de `readAllBytes` pour garder la memoire
     * bornee meme sur des gros PDF multi-pages).
     */
    fun sha256Of(filePath: Path): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(filePath).use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            log.warn("SHA-256 computation failed for {}: {}", filePath, e.message)
            null
        }
    }

    /**
     * Lit le cache pour la cle donnee. Incrementer hit_count/last_hit_at doit
     * se faire dans une transaction courte, isolee du reste du pipeline OCR :
     * si l'update echoue (par ex. H2 en mode test), on ne bloque pas la lecture.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun lookup(sha256: String): CachedOcr? {
        if (!enabled) return null
        val entry = repo.findById(sha256).orElse(null) ?: return null
        if (entry.cacheVersion != cacheVersion) return null
        try {
            repo.markHit(sha256, LocalDateTime.now())
        } catch (e: Exception) {
            log.debug("markHit failed for {}: {}", sha256, e.message)
        }
        return CachedOcr(
            text = entry.text,
            engine = entry.engine,
            pageCount = entry.pageCount,
            confidence = entry.confidence
        )
    }

    /**
     * Insertion best-effort. Doublon (course entre deux threads sur le meme
     * SHA-256) -> on ignore, l'autre thread a gagne.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun store(sha256: String, value: CachedOcr) {
        if (!enabled) return
        if (value.text.isBlank()) return
        val now = LocalDateTime.now()
        val entry = OcrCacheEntry(
            sha256 = sha256,
            cacheVersion = cacheVersion,
            text = value.text,
            engine = value.engine,
            pageCount = value.pageCount,
            confidence = value.confidence,
            createdAt = now,
            lastHitAt = now,
            hitCount = 0
        )
        try {
            repo.save(entry)
        } catch (e: DataIntegrityViolationException) {
            log.debug("OCR cache race on {}: entry already stored", sha256)
        } catch (e: Exception) {
            log.warn("Failed to persist OCR cache entry {}: {}", sha256, e.message)
        }
    }
}
