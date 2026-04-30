package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.repository.ClaudeUsageRepository
import com.madaef.recondoc.service.AppSettingsService
import com.madaef.recondoc.service.extraction.CallKind
import com.madaef.recondoc.service.extraction.ChainOfVerificationService
import com.madaef.recondoc.service.extraction.ClaudeToolResponse
import com.madaef.recondoc.service.extraction.LlmExtractionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verrouille le contrat de Chain-of-Verification :
 *  - skip si desactive (kill switch)
 *  - skip si type sans champs critiques
 *  - skip si tous les champs critiques sont null (0 appel Claude)
 *  - strip + warning si presentInSource=false
 *  - strip + warning si citation introuvable dans le texte OCR
 *  - accepte si presentInSource=true sans citation (no-penalty)
 *  - exception Claude -> garde l'extraction du run 1 (no breaking)
 *
 * Suit le pattern IdentifierConsistencyServiceTest : sous-classe LlmExtraction
 * Service pour stubber callClaudeToolWithTemperature.
 */
class ChainOfVerificationServiceTest {

    private val appSettings = mock(AppSettingsService::class.java).also {
        `when`(it.isChainOfVerificationEnabled()).thenReturn(true)
    }

    private class CallCounter {
        var calls: Int = 0
        var lastFields: List<Pair<String, String>> = emptyList()
    }

    private class FakeLlm(
        private val response: (List<Pair<String, String>>) -> Map<String, Any?>,
        private val counter: CallCounter,
        private val throwOnCall: Boolean = false
    ) : LlmExtractionService(
        objectMapper = JsonMapper(),
        appSettingsService = mock(AppSettingsService::class.java),
        claudeUsageRepository = mock(ClaudeUsageRepository::class.java)
    ) {
        override val isAvailable = true
        override fun callClaudeToolWithTemperature(
            systemPrompt: String, userContent: String, toolName: String,
            inputSchema: Map<String, Any>, kind: CallKind, temperatureOverride: Double
        ): ClaudeToolResponse {
            counter.calls++
            if (throwOnCall) throw RuntimeException("simulated network failure")
            val regex = Regex("- field: (\\w+), value: \"([^\"]+)\"")
            val entries = regex.findAll(userContent).map { it.groupValues[1] to it.groupValues[2] }.toList()
            counter.lastFields = entries
            return ClaudeToolResponse(toolInput = response(entries), stopReason = "tool_use")
        }
    }

    private fun acceptAll(): (List<Pair<String, String>>) -> Map<String, Any?> = { entries ->
        mapOf("verifications" to entries.map { (f, v) ->
            mapOf("field" to f, "presentInSource" to true, "citation" to v, "reason" to "ok")
        })
    }

    @Test
    fun `skip si CoVe desactive (kill switch)`() {
        val disabled = mock(AppSettingsService::class.java).also {
            `when`(it.isChainOfVerificationEnabled()).thenReturn(false)
        }
        val counter = CallCounter()
        val svc = ChainOfVerificationService(FakeLlm(acceptAll(), counter), disabled)
        val data = mapOf("ice" to "001509176000008", "numeroFacture" to "F-2026-001")
        val r = svc.verify(TypeDocument.FACTURE, data, "ICE 001509176000008 F-2026-001")
        assertEquals(data, r.cleanedData)
        assertEquals(0, counter.calls, "kill switch -> 0 appel Claude")
    }

    @Test
    fun `skip si type non concerne (PV reception)`() {
        val counter = CallCounter()
        val svc = ChainOfVerificationService(FakeLlm(acceptAll(), counter), appSettings)
        val data = mapOf("dateReception" to "2026-03-15")
        val r = svc.verify(TypeDocument.PV_RECEPTION, data, "PV 15/03/2026")
        assertEquals(data, r.cleanedData)
        assertEquals(0, counter.calls)
    }

    @Test
    fun `skip si tous les champs critiques sont absents (0 appel Claude)`() {
        val counter = CallCounter()
        val svc = ChainOfVerificationService(FakeLlm(acceptAll(), counter), appSettings)
        val data = mapOf<String, Any?>("client" to "MADAEF") // pas de champ critique
        val r = svc.verify(TypeDocument.FACTURE, data, "Facture")
        assertEquals(data, r.cleanedData)
        assertEquals(0, counter.calls, "Aucun champ critique present -> 0 appel Claude")
    }

    @Test
    fun `champ verifie present accepte garde la valeur`() {
        val counter = CallCounter()
        val svc = ChainOfVerificationService(FakeLlm(acceptAll(), counter), appSettings)
        val data = mapOf<String, Any?>(
            "ice" to "001509176000008",
            "numeroFacture" to "F-2026-001"
        )
        val text = "FACTURE N F-2026-001\nICE : 001509176000008\n"
        val r = svc.verify(TypeDocument.FACTURE, data, text)
        assertEquals("001509176000008", r.cleanedData["ice"])
        assertEquals("F-2026-001", r.cleanedData["numeroFacture"])
        assertTrue(r.unverified.isEmpty())
    }

    @Test
    fun `champ marque non present est strip`() {
        val counter = CallCounter()
        val response = { entries: List<Pair<String, String>> ->
            mapOf("verifications" to entries.map { (f, _) ->
                if (f == "ice") mapOf<String, Any?>(
                    "field" to "ice", "presentInSource" to false,
                    "citation" to null, "reason" to "ICE absent du texte"
                )
                else mapOf<String, Any?>(
                    "field" to f, "presentInSource" to true,
                    "citation" to "ok", "reason" to "ok"
                )
            })
        }
        val svc = ChainOfVerificationService(FakeLlm(response, counter), appSettings)
        val data = mapOf<String, Any?>(
            "ice" to "999888777666555",
            "numeroFacture" to "F-2026-001"
        )
        val r = svc.verify(TypeDocument.FACTURE, data, "FACTURE F-2026-001")
        assertNull(r.cleanedData["ice"], "ICE non present doit etre strip")
        assertEquals("F-2026-001", r.cleanedData["numeroFacture"])
        assertEquals(1, r.unverified.size)
        assertEquals("ice", r.unverified.first().field)
    }

    @Test
    fun `citation marquee presente mais introuvable dans OCR strip le champ`() {
        val counter = CallCounter()
        val response = { entries: List<Pair<String, String>> ->
            mapOf("verifications" to entries.map { (f, _) ->
                mapOf<String, Any?>(
                    "field" to f, "presentInSource" to true,
                    "citation" to "phrase totalement inventee impossible a trouver dans le texte",
                    "reason" to "valide"
                )
            })
        }
        val svc = ChainOfVerificationService(FakeLlm(response, counter), appSettings)
        val data = mapOf<String, Any?>("numeroFacture" to "F-2026-001", "ice" to "001509176000008")
        val text = "FACTURE F-2026-001\nICE 001509176000008\n"
        val r = svc.verify(TypeDocument.FACTURE, data, text)
        assertNull(r.cleanedData["numeroFacture"], "Citation absente du texte -> strip")
        assertNull(r.cleanedData["ice"])
        assertEquals(2, r.unverified.size)
    }

    @Test
    fun `present sans citation accepte sans penalite (autres couches font le travail)`() {
        val counter = CallCounter()
        val response = { entries: List<Pair<String, String>> ->
            mapOf("verifications" to entries.map { (f, _) ->
                mapOf<String, Any?>(
                    "field" to f, "presentInSource" to true,
                    "citation" to null, "reason" to "ok no quote"
                )
            })
        }
        val svc = ChainOfVerificationService(FakeLlm(response, counter), appSettings)
        val data = mapOf<String, Any?>("numeroFacture" to "F-2026-001")
        val r = svc.verify(TypeDocument.FACTURE, data, "FACTURE F-2026-001")
        assertEquals("F-2026-001", r.cleanedData["numeroFacture"])
        assertTrue(r.unverified.isEmpty())
    }

    @Test
    fun `exception Claude conserve l extraction du run 1`() {
        val counter = CallCounter()
        val svc = ChainOfVerificationService(
            FakeLlm(acceptAll(), counter, throwOnCall = true),
            appSettings
        )
        val data = mapOf<String, Any?>("numeroFacture" to "F-2026-001", "ice" to "001509176000008")
        val r = svc.verify(TypeDocument.FACTURE, data, "FACTURE F-2026-001")
        assertEquals(data, r.cleanedData, "Echec CoVe ne doit PAS casser le run 1")
        assertTrue(r.unverified.isEmpty())
        assertEquals(1, counter.calls, "Un seul essai (pas de retry au sein du service)")
    }

    @Test
    fun `warnings precedents preserves et concatenes`() {
        val counter = CallCounter()
        val response = { entries: List<Pair<String, String>> ->
            mapOf("verifications" to entries.map { (f, _) ->
                mapOf<String, Any?>(
                    "field" to f, "presentInSource" to false,
                    "citation" to null, "reason" to "ko"
                )
            })
        }
        val svc = ChainOfVerificationService(FakeLlm(response, counter), appSettings)
        val data = mapOf<String, Any?>(
            "numeroFacture" to "F-2026-001",
            "_warnings" to listOf("OCR confidence low")
        )
        val r = svc.verify(TypeDocument.FACTURE, data, "FACTURE")
        @Suppress("UNCHECKED_CAST")
        val warnings = r.cleanedData["_warnings"] as List<String>
        assertTrue(warnings.size >= 2)
        assertTrue(warnings.any { it.contains("OCR confidence low") })
        assertTrue(warnings.any { it.contains("Chain-of-Verification") })
    }

    @Test
    fun `OP champs critiques verifies (numeroOp rib beneficiaire)`() {
        val counter = CallCounter()
        val svc = ChainOfVerificationService(FakeLlm(acceptAll(), counter), appSettings)
        val data = mapOf<String, Any?>(
            "numeroOp" to "OP-2026-0042",
            "rib" to "011810000000012345678902",
            "beneficiaire" to "ACME SARL",
            "montantOperation" to 12000.00,
            "dateEmission" to "2026-04-10"
        )
        svc.verify(
            TypeDocument.ORDRE_PAIEMENT, data,
            "OP-2026-0042 ACME SARL RIB 011810000000012345678902 12000.00 2026-04-10"
        )
        val verifiedFields = counter.lastFields.map { it.first }
        assertTrue(verifiedFields.contains("numeroOp"))
        assertTrue(verifiedFields.contains("rib"))
        assertTrue(verifiedFields.contains("beneficiaire"))
        assertNotNull(verifiedFields.find { it == "montantOperation" }, "montantOperation est critique sur OP")
    }
}
