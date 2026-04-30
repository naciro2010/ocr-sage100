package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.repository.ClaudeUsageRepository
import com.madaef.recondoc.service.AppSettingsService
import com.madaef.recondoc.service.extraction.ClaudeToolResponse
import com.madaef.recondoc.service.extraction.CrossModelVerificationService
import com.madaef.recondoc.service.extraction.LlmExtractionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verrouille le contrat de cross-model verification :
 *  - skip si desactive
 *  - skip si type non applicable
 *  - skip si aucun identifiant non-null dans le run principal (0 appel Haiku)
 *  - accord -> aucune action
 *  - divergence -> strip + warning critique
 *  - non-confirmation (Haiku=null) -> warning sans strip (valeur preservee)
 *  - exception Haiku -> conserve le run principal (no breaking)
 */
class CrossModelVerificationServiceTest {

    private val appSettings = mock(AppSettingsService::class.java).also {
        `when`(it.isCrossModelVerificationEnabled()).thenReturn(true)
        `when`(it.getCrossModelVerificationModel()).thenReturn("claude-haiku-4-5-20251001")
    }

    private class FakeLlm(
        private val crossModelResponse: Map<String, Any?>,
        private val throwOnCall: Boolean = false
    ) : LlmExtractionService(
        objectMapper = JsonMapper(),
        appSettingsService = mock(AppSettingsService::class.java),
        claudeUsageRepository = mock(ClaudeUsageRepository::class.java)
    ) {
        override val isAvailable = true
        var calls: Int = 0
        override fun callClaudeToolWithModel(
            systemPrompt: String, userContent: String, toolName: String,
            inputSchema: Map<String, Any>, modelOverride: String, temperatureOverride: Double
        ): ClaudeToolResponse {
            calls++
            if (throwOnCall) throw RuntimeException("simulated Haiku failure")
            return ClaudeToolResponse(toolInput = crossModelResponse, stopReason = "tool_use")
        }
    }

    @Test
    fun `skip si cross-model desactive (kill switch)`() {
        val disabled = mock(AppSettingsService::class.java).also {
            `when`(it.isCrossModelVerificationEnabled()).thenReturn(false)
        }
        val llm = FakeLlm(mapOf("ice" to "999999999999999"))
        val svc = CrossModelVerificationService(llm, disabled)
        val r = svc.verify(
            TypeDocument.FACTURE,
            mapOf("ice" to "001509176000008"),
            "ICE 001509176000008"
        )
        assertFalse(r.hasCriticalDiscrepancy)
        assertEquals(0, llm.calls)
        assertEquals("001509176000008", r.cleanedData["ice"])
    }

    @Test
    fun `skip si type non applicable (BC operationnel)`() {
        val llm = FakeLlm(mapOf("ice" to "999"))
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.BON_COMMANDE,
            mapOf("reference" to "BC-2026-001"),
            "BC"
        )
        assertEquals(0, llm.calls, "BC operationnel n'a pas d'identifiant critique")
        assertFalse(r.hasCriticalDiscrepancy)
    }

    @Test
    fun `skip si aucun identifiant present dans le run principal (0 appel Haiku)`() {
        val llm = FakeLlm(mapOf("ice" to "001509176000008"))
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.FACTURE,
            mapOf("ice" to null, "rib" to null, "identifiantFiscal" to null),
            "Facture sans identifiants"
        )
        assertEquals(0, llm.calls, "Aucun identifiant non-null -> ne pas appeler Haiku")
        assertFalse(r.hasCriticalDiscrepancy)
    }

    @Test
    fun `accord Sonnet et Haiku sur ICE - aucun warning`() {
        val llm = FakeLlm(mapOf("ice" to "001509176000008"))
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.FACTURE,
            mapOf("ice" to "001509176000008"),
            "ICE 001509176000008"
        )
        assertFalse(r.hasCriticalDiscrepancy)
        assertTrue(r.discrepancies.isEmpty())
        assertEquals("001509176000008", r.cleanedData["ice"])
    }

    @Test
    fun `divergence Sonnet vs Haiku sur ICE critique - strip + warning`() {
        val llm = FakeLlm(mapOf("ice" to "999888777666555")) // Haiku trouve different
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.FACTURE,
            mapOf("ice" to "001509176000008"),
            "ICE 001509176000008"
        )
        assertTrue(r.hasCriticalDiscrepancy)
        assertNull(r.cleanedData["ice"], "ICE divergent doit etre strip")
        val warnings = r.cleanedData["_warnings"] as? List<*>
        assertNotNull(warnings)
        assertTrue(warnings.any { it.toString().contains("Cross-model violation on ice") })
    }

    @Test
    fun `non-confirmation Haiku null - warning sans strip (valeur preservee)`() {
        // Haiku ne trouve pas l'ICE (peut-etre OCR different), mais Sonnet l'a trouve.
        // On NE strip PAS automatiquement (Haiku peut avoir mal lu) mais on flag.
        val llm = FakeLlm(mapOf("ice" to null))
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.FACTURE,
            mapOf("ice" to "001509176000008"),
            "ICE 001509176000008"
        )
        assertTrue(r.hasCriticalDiscrepancy, "Non-confirmation = critical (signal de doute)")
        assertEquals("001509176000008", r.cleanedData["ice"], "Valeur preservee, juste warning")
        val warnings = r.cleanedData["_warnings"] as? List<*>
        assertNotNull(warnings)
        assertTrue(warnings.any { it.toString().contains("Cross-model unconfirmed on ice") })
    }

    @Test
    fun `RIB non critique sur FACTURE - divergence strip mais hasCritical=false`() {
        val llm = FakeLlm(mapOf("rib" to "111111111111111111111111"))
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.FACTURE,
            mapOf("rib" to "230810000150002775637823"),
            "RIB different"
        )
        assertFalse(r.hasCriticalDiscrepancy, "RIB non critique sur FACTURE")
        assertNull(r.cleanedData["rib"], "Mais strip quand meme")
    }

    @Test
    fun `OP RIB est critique - divergence cause hasCritical=true`() {
        val llm = FakeLlm(mapOf("rib" to "111111111111111111111111"))
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.ORDRE_PAIEMENT,
            mapOf("rib" to "230810000150002775637823"),
            "OP avec RIB different"
        )
        assertTrue(r.hasCriticalDiscrepancy, "RIB sur OP est critique")
        assertNull(r.cleanedData["rib"])
    }

    @Test
    fun `confusion OCR O vs 0 normalisee avant comparaison - pas de fausse divergence`() {
        // Sonnet renvoie "001509176000008", Haiku renvoie "OO1509176000008"
        // (confusion O vs 0). Apres normalisation OcrConfusions, les deux donnent
        // les memes 15 chiffres -> accord.
        val llm = FakeLlm(mapOf("ice" to "OO1509176000008"))
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.FACTURE,
            mapOf("ice" to "001509176000008"),
            "ICE 001509176000008"
        )
        assertFalse(r.hasCriticalDiscrepancy, "Confusion OCR O/0 ne doit PAS etre une divergence")
    }

    @Test
    fun `exception Haiku conserve le run principal (no breaking)`() {
        val llm = FakeLlm(emptyMap(), throwOnCall = true)
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.FACTURE,
            mapOf("ice" to "001509176000008"),
            "ICE 001509176000008"
        )
        assertFalse(r.hasCriticalDiscrepancy)
        assertTrue(r.discrepancies.isEmpty())
        assertEquals("001509176000008", r.cleanedData["ice"])
    }

    @Test
    fun `warnings precedents preserves et concatenes`() {
        val llm = FakeLlm(mapOf("ice" to "999"))
        val svc = CrossModelVerificationService(llm, appSettings)
        val r = svc.verify(
            TypeDocument.FACTURE,
            mapOf(
                "ice" to "001509176000008",
                "_warnings" to listOf("Warning precedent")
            ),
            "ICE 001509176000008"
        )
        @Suppress("UNCHECKED_CAST")
        val warnings = r.cleanedData["_warnings"] as List<String>
        assertTrue(warnings.size >= 2)
        assertTrue(warnings.any { it.contains("Warning precedent") })
        assertTrue(warnings.any { it.contains("Cross-model") })
    }
}
