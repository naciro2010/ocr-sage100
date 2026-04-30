package com.madaef.recondoc

import com.madaef.recondoc.service.extraction.ExtractionCacheService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verrouille le contrat du cache deterministe d'extractions Claude :
 *  - meme prompt + texte + schema = meme cle = meme reponse
 *  - difference dans n'importe quel composant -> cle differente
 *  - max_tokens -> pas de store (filet anti-figement de reponse partielle)
 *  - LRU eviction au-dela de max_entries
 *  - desactivation propre (kill switch)
 */
class ExtractionCacheServiceTest {

    private fun newService(maxEntries: Int = 1024, ttlSec: Long = 3600, enabled: Boolean = true) =
        ExtractionCacheService(enabled = enabled, maxEntries = maxEntries, ttlSeconds = ttlSec)

    @Test
    fun `meme entree produit la meme cle`() {
        val svc = newService()
        val k1 = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user", "{}", 0.0)
        val k2 = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user", "{}", 0.0)
        assertEquals(k1, k2)
        assertNotNull(k1)
        assertEquals(64, k1.length, "SHA-256 hex = 64 chars")
    }

    @Test
    fun `cle change si systemPrompt change`() {
        val svc = newService()
        val k1 = svc.keyFor("sonnet-4.6", "extract_facture", "system A", "user", "{}", 0.0)
        val k2 = svc.keyFor("sonnet-4.6", "extract_facture", "system B", "user", "{}", 0.0)
        assertTrue(k1 != k2)
    }

    @Test
    fun `cle change si userContent change (texte OCR different)`() {
        val svc = newService()
        val k1 = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user A", "{}", 0.0)
        val k2 = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user B", "{}", 0.0)
        assertTrue(k1 != k2)
    }

    @Test
    fun `cle change si schema change (nouveau champ)`() {
        val svc = newService()
        val k1 = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user", "{\"v\":1}", 0.0)
        val k2 = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user", "{\"v\":2}", 0.0)
        assertTrue(k1 != k2)
    }

    @Test
    fun `cle change si modele change (Sonnet vs Haiku)`() {
        val svc = newService()
        val k1 = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user", "{}", 0.0)
        val k2 = svc.keyFor("haiku-4.5", "extract_facture", "system", "user", "{}", 0.0)
        assertTrue(k1 != k2)
    }

    @Test
    fun `cle change si temperature change`() {
        val svc = newService()
        val k1 = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user", "{}", 0.0)
        val k2 = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user", "{}", 0.7)
        assertTrue(k1 != k2)
    }

    @Test
    fun `pas de collision par recombinaison (separateur)`() {
        // Cas pathologique : deux prompts qui pourraient se recombiner sans
        // separateur produiraient la meme cle. Le separateur 0x01 protege.
        val svc = newService()
        val k1 = svc.keyFor("a", "b", "cd", "ef", "g", 0.0)
        val k2 = svc.keyFor("a", "bc", "d", "ef", "g", 0.0)
        assertTrue(k1 != k2, "Recombinaison possible sans separateur")
    }

    @Test
    fun `lookup d'une cle absente retourne null`() {
        val svc = newService()
        assertNull(svc.lookup("0".repeat(64)))
    }

    @Test
    fun `store puis lookup retourne la reponse cachee`() {
        val svc = newService()
        val key = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user", "{}", 0.0)!!
        val data = mapOf("numeroFacture" to "F-2026-001", "_confidence" to 0.95)
        svc.store(key, data, "tool_use")

        val hit = svc.lookup(key)
        assertNotNull(hit)
        assertEquals(data, hit.toolInput)
        assertEquals("tool_use", hit.stopReason)
    }

    @Test
    fun `store ne cache PAS une reponse tronquee max_tokens`() {
        // Cacher une reponse partielle figerait l'erreur sur les futures
        // re-extractions du meme document. Le caller relance avec un budget
        // plus large, donc on doit laisser passer.
        val svc = newService()
        val key = svc.keyFor("sonnet-4.6", "extract_facture", "system", "user", "{}", 0.0)!!
        svc.store(key, mapOf("numeroFacture" to "F-2026"), "max_tokens")

        assertNull(svc.lookup(key), "max_tokens NE doit PAS etre cache")
    }

    @Test
    fun `eviction LRU au-dela de max_entries`() {
        val svc = newService(maxEntries = 2)
        val k1 = svc.keyFor("m", "t", "s", "user1", "{}", 0.0)!!
        val k2 = svc.keyFor("m", "t", "s", "user2", "{}", 0.0)!!
        val k3 = svc.keyFor("m", "t", "s", "user3", "{}", 0.0)!!

        svc.store(k1, mapOf("v" to 1), "tool_use")
        svc.store(k2, mapOf("v" to 2), "tool_use")
        // Acces a k1 -> remonte k1 dans LRU, k2 devient l'eldest
        svc.lookup(k1)
        svc.store(k3, mapOf("v" to 3), "tool_use")

        assertNotNull(svc.lookup(k1), "k1 recemment lu, doit rester")
        assertNull(svc.lookup(k2), "k2 LRU, doit etre evicte")
        assertNotNull(svc.lookup(k3))
        assertEquals(2, svc.size())
    }

    @Test
    fun `TTL expire eviction lazy au lookup`() {
        val svc = newService(ttlSec = 0) // expire immediatement
        val key = svc.keyFor("m", "t", "s", "u", "{}", 0.0)!!
        svc.store(key, mapOf("v" to 1), "tool_use")
        // Sleep 1 sec pour passer le TTL=0
        Thread.sleep(1100)
        assertNull(svc.lookup(key), "TTL depasse -> miss et eviction")
    }

    @Test
    fun `cache desactive ne stocke ni ne lit rien`() {
        val svc = newService(enabled = false)
        val key = svc.keyFor("m", "t", "s", "u", "{}", 0.0)
        assertNotNull(key, "keyFor reste fonctionnel meme desactive (utilise pour logs)")
        svc.store(key, mapOf("v" to 1), "tool_use")
        assertNull(svc.lookup(key), "kill switch -> 0 hit")
        assertEquals(0, svc.size())
    }

    @Test
    fun `clear vide le cache`() {
        val svc = newService()
        val key = svc.keyFor("m", "t", "s", "u", "{}", 0.0)!!
        svc.store(key, mapOf("v" to 1), "tool_use")
        assertEquals(1, svc.size())
        svc.clear()
        assertEquals(0, svc.size())
        assertNull(svc.lookup(key))
    }
}
