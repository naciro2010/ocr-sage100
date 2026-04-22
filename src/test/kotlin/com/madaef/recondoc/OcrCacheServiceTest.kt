package com.madaef.recondoc

import com.madaef.recondoc.entity.OcrCacheEntry
import com.madaef.recondoc.repository.OcrCacheRepository
import com.madaef.recondoc.service.OcrCacheService
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Helpers Kotlin-friendly pour Mockito : ArgumentMatchers.any(Class<T>) renvoie
// null cote JVM, ce qui explose sur les parametres non-null de Kotlin. On
// enregistre le matcher (effet de bord Mockito) et on renvoie un placeholder
// non-null : Mockito ignore la valeur retournee, il lit le matcher du stack.
private fun anyLocalDateTime(): LocalDateTime {
    ArgumentMatchers.any(LocalDateTime::class.java)
    return LocalDateTime.MIN
}

private fun anyOcrCacheEntry(): OcrCacheEntry {
    ArgumentMatchers.any(OcrCacheEntry::class.java)
    return OcrCacheEntry()
}

private fun eqStr(value: String): String {
    ArgumentMatchers.eq(value)
    return value
}

class OcrCacheServiceTest {

    private fun svc(
        repo: OcrCacheRepository = mock(OcrCacheRepository::class.java),
        enabled: Boolean = true,
        version: String = "v1"
    ): Pair<OcrCacheService, OcrCacheRepository> {
        return OcrCacheService(repo, enabled, version) to repo
    }

    @Test
    fun `sha256Of retourne un hash hex de 64 caracteres stable`() {
        val (service, _) = svc()
        val tmp = Files.createTempFile("ocr-cache-", ".bin")
        try {
            Files.writeString(tmp, "contenu test OCR")
            val h1 = service.sha256Of(tmp)
            val h2 = service.sha256Of(tmp)
            assertNotNull(h1)
            assertEquals(h1, h2, "hash doit etre stable pour le meme contenu")
            assertEquals(64, h1!!.length, "SHA-256 hex = 64 chars")
            assertTrue(h1.all { it in '0'..'9' || it in 'a'..'f' }, "hex lowercase attendu")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `sha256Of change quand le contenu change`() {
        val (service, _) = svc()
        val tmp = Files.createTempFile("ocr-cache-", ".bin")
        try {
            Files.writeString(tmp, "v1")
            val h1 = service.sha256Of(tmp)
            Files.writeString(tmp, "v2")
            val h2 = service.sha256Of(tmp)
            assertTrue(h1 != h2, "contenus differents doivent produire des hashes differents")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `lookup renvoie null quand la cle n'existe pas`() {
        val (service, repo) = svc()
        `when`(repo.findById(ArgumentMatchers.anyString())).thenReturn(Optional.empty())
        assertNull(service.lookup("deadbeef"))
    }

    @Test
    fun `lookup renvoie l'entree et incremente hit_count`() {
        val (service, repo) = svc()
        val entry = OcrCacheEntry(
            sha256 = "abc", cacheVersion = "v1", text = "bonjour",
            engine = "TIKA", pageCount = 3, confidence = 0.9,
            createdAt = LocalDateTime.now(), lastHitAt = LocalDateTime.now(), hitCount = 0
        )
        `when`(repo.findById("abc")).thenReturn(Optional.of(entry))
        val hit = service.lookup("abc")
        assertNotNull(hit)
        assertEquals("bonjour", hit!!.text)
        assertEquals("TIKA", hit.engine)
        assertEquals(3, hit.pageCount)
        assertEquals(0.9, hit.confidence)
        verify(repo).markHit(eqStr("abc"), anyLocalDateTime())
    }

    @Test
    fun `lookup renvoie null si cache_version differe`() {
        val (service, repo) = svc(version = "v2")
        val stale = OcrCacheEntry(
            sha256 = "abc", cacheVersion = "v1", text = "vieux",
            engine = "TIKA", pageCount = 1, confidence = 0.5
        )
        `when`(repo.findById("abc")).thenReturn(Optional.of(stale))
        assertNull(service.lookup("abc"), "version mismatch = miss")
    }

    @Test
    fun `lookup retourne null quand desactive`() {
        val repo = mock(OcrCacheRepository::class.java)
        val (service, _) = svc(repo = repo, enabled = false)
        assertNull(service.lookup("abc"))
        verify(repo, never()).findById(ArgumentMatchers.anyString())
    }

    @Test
    fun `store persiste l'entree avec la version courante`() {
        val (service, repo) = svc(version = "v1")
        val captor = ArgumentCaptor.forClass(OcrCacheEntry::class.java)
        service.store(
            "abc",
            OcrCacheService.CachedOcr(text = "texte", engine = "MISTRAL_OCR", pageCount = 2, confidence = 0.95)
        )
        verify(repo).save(captor.capture())
        val saved = captor.value
        assertEquals("abc", saved.sha256)
        assertEquals("v1", saved.cacheVersion)
        assertEquals("texte", saved.text)
        assertEquals("MISTRAL_OCR", saved.engine)
        assertEquals(2, saved.pageCount)
        assertEquals(0.95, saved.confidence)
        assertEquals(0, saved.hitCount)
    }

    @Test
    fun `store ignore un texte vide`() {
        val (service, repo) = svc()
        service.store("abc", OcrCacheService.CachedOcr("", "TIKA", 1, 0.0))
        verify(repo, never()).save(anyOcrCacheEntry())
    }

    @Test
    fun `store avale un doublon concurrent sans propager l'exception`() {
        val (service, repo) = svc()
        `when`(repo.save(anyOcrCacheEntry())).thenThrow(DataIntegrityViolationException("dup"))
        service.store("abc", OcrCacheService.CachedOcr("texte", "TIKA", 1, 0.5))
    }

    @Test
    fun `store no-op quand desactive`() {
        val repo = mock(OcrCacheRepository::class.java)
        val (service, _) = svc(repo = repo, enabled = false)
        service.store("abc", OcrCacheService.CachedOcr("texte", "TIKA", 1, 0.0))
        verify(repo, never()).save(anyOcrCacheEntry())
    }
}
