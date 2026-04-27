package com.madaef.recondoc

import tools.jackson.databind.json.JsonMapper
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.repository.ClaudeUsageRepository
import com.madaef.recondoc.service.AppSettingsService
import com.madaef.recondoc.service.extraction.CallKind
import com.madaef.recondoc.service.extraction.ClaudeToolResponse
import com.madaef.recondoc.service.extraction.IdentifierConsistencyService
import com.madaef.recondoc.service.extraction.LlmExtractionService
import com.madaef.recondoc.service.extraction.PseudonymizationService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verrouille le comportement self-consistency :
 *  - run1 == run2 (apres normalisation OCR) -> aucun warning, donnees gardees
 *  - run1 != run2 -> strip + warning explicite
 *  - run1 non-null + run2 null -> warning de non-confirmation, valeur gardee
 *    (signal d'hallucination potentielle, sans certitude)
 *
 * Les @Service Kotlin sont ouverts par le plugin kotlin-spring : on peut les
 * sous-classer pour stubber callClaudeToolWithTemperature sans Mockito (qui
 * ne joue pas bien avec les parametres non-nullables Kotlin).
 */
class IdentifierConsistencyServiceTest {

    private val appSettings = mock(AppSettingsService::class.java).also {
        `when`(it.isIdentifierConsistencyEnabled()).thenReturn(true)
        `when`(it.getIdentifierConsistencyTemperature()).thenReturn(0.5)
    }
    private val pseudo = PseudonymizationService(appSettings)

    private class FakeLlm(private val secondRun: Map<String, Any?>) : LlmExtractionService(
        objectMapper = JsonMapper(),
        appSettingsService = mock(AppSettingsService::class.java),
        claudeUsageRepository = mock(ClaudeUsageRepository::class.java)
    ) {
        override val isAvailable = true
        override fun callClaudeToolWithTemperature(
            systemPrompt: String, userContent: String, toolName: String,
            inputSchema: Map<String, Any>, kind: CallKind, temperatureOverride: Double
        ): ClaudeToolResponse = ClaudeToolResponse(toolInput = secondRun, stopReason = "end_turn")
    }

    @Test
    fun `run1 et run2 identiques apres normalisation - pas de warning`() {
        val svc = IdentifierConsistencyService(
            FakeLlm(mapOf("ice" to "001509176000008")),
            appSettings, pseudo
        )
        val run1 = mapOf<String, Any?>("ice" to "001509176000008")
        val res = svc.verify(TypeDocument.FACTURE, run1, "ICE 001509176000008")

        assertTrue(res.discrepancies.isEmpty())
        assertEquals("001509176000008", res.cleanedData["ice"])
        assertNull(res.cleanedData["_warnings"])
    }

    @Test
    fun `run1 et run2 differents - strip + warning critique`() {
        val svc = IdentifierConsistencyService(
            FakeLlm(mapOf("ice" to "999888777666555")),
            appSettings, pseudo
        )
        val run1 = mapOf<String, Any?>("ice" to "001509176000008")
        val res = svc.verify(TypeDocument.FACTURE, run1, "ICE ambigu")

        assertTrue(res.hasCriticalDiscrepancy)
        assertNull(res.cleanedData["ice"], "ICE divergent doit etre strip")
        val warnings = res.cleanedData["_warnings"] as? List<*>
        assertNotNull(warnings)
        assertTrue(warnings.any { it.toString().contains("Self-consistency violation on ice") })
    }

    @Test
    fun `run1 non-null + run2 null - warning de non-confirmation, valeur gardee`() {
        val svc = IdentifierConsistencyService(
            FakeLlm(mapOf("ice" to null)),
            appSettings, pseudo
        )
        val run1 = mapOf<String, Any?>("ice" to "001509176000008")
        val res = svc.verify(TypeDocument.FACTURE, run1, "ICE potentiellement halluciné")

        assertTrue(res.hasCriticalDiscrepancy, "non-confirmation doit lever critical=true (signal de doute)")
        assertEquals("001509176000008", res.cleanedData["ice"], "valeur preservee, juste warning")
        val warnings = res.cleanedData["_warnings"] as? List<*>
        assertNotNull(warnings)
        assertTrue(warnings.any { it.toString().contains("Self-consistency unconfirmed on ice") })
    }

    @Test
    fun `run1 null - aucun warning meme si run2 different`() {
        val svc = IdentifierConsistencyService(
            FakeLlm(mapOf("ice" to "999888777666555")),
            appSettings, pseudo
        )
        val run1 = mapOf<String, Any?>("ice" to null, "rib" to "230810000150002775637823")
        val res = svc.verify(TypeDocument.FACTURE, run1, "RIB seul")

        assertTrue(res.discrepancies.none { it.field == "ice" })
    }
}
