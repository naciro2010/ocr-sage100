package com.madaef.recondoc

import com.fasterxml.jackson.databind.ObjectMapper
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.repository.ClaudeUsageRepository
import com.madaef.recondoc.service.AppSettingsService
import com.madaef.recondoc.service.extraction.CallKind
import com.madaef.recondoc.service.extraction.ClassificationService
import com.madaef.recondoc.service.extraction.ClaudeToolResponse
import com.madaef.recondoc.service.extraction.LlmExtractionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Couvre le chemin keyword-based (rapide, 0 appel LLM) et le chemin LLM
 * tool_use via un test double qui sous-classe LlmExtractionService. Les
 * @Service Kotlin sont ouverts par le plugin kotlin-spring, donc
 * l'heritage fonctionne sans passer par Mockito (null-safety Kotlin).
 */
class ClassificationServiceTest {

    private val mockLlm = mock(LlmExtractionService::class.java)
    private val service = ClassificationService(mockLlm)

    private val ambiguousText = """
        Document divers sans marqueur clair.
        Page interne d'un rapport.
        Pas de numero facture, pas de BC, pas de PV.
    """.trimIndent()

    @Test
    fun `facture avec reference BC dans le corps reste classee FACTURE`() {
        val text = """
            FACTURE N DEV-2026-0142
            Date: 15/03/2026
            Fournisseur: ACME SARL

            Designation: Prestation entretien mensuelle
            Reference Bon de Commande: CF SIE 2026-1234

            Montant HT: 10 000,00
            TVA 20%:    2 000,00
            Montant TTC:12 000,00
            Net a payer:12 000,00
        """.trimIndent()
        assertEquals(TypeDocument.FACTURE, service.classify(text))
    }

    @Test
    fun `bon de commande pur est classe BON_COMMANDE`() {
        val text = """
            BON DE COMMANDE N CF SIE 2026-1234
            Date emission: 01/02/2026
            Fournisseur: ACME SARL

            Designation Quantite PU HT Montant HT
            Entretien espaces verts  12  1000.00  12000.00

            Montant HT: 12 000,00
            TVA 20%: 2 400,00
            Montant TTC: 14 400,00
        """.trimIndent()
        assertEquals(TypeDocument.BON_COMMANDE, service.classify(text))
    }

    @Test
    fun `ordre de paiement est classe ORDRE_PAIEMENT meme si cite tableau de controle`() {
        val text = """
            ORDRE DE PAIEMENT N OP-2026-001
            Beneficiaire: ACME SARL

            Synthese du Controleur Financier:
            Montant brut: 12000
            Retenue TVA source: 500
            Net a payer: 11500

            Pieces jointes: facture, BC, tableau de controle, PV reception
        """.trimIndent()
        assertEquals(TypeDocument.ORDRE_PAIEMENT, service.classify(text))
    }

    @Test
    fun `tableau de controle sans ordre de paiement est classe TABLEAU_CONTROLE`() {
        val text = """
            Tableau de controle financier
            Societe: MADAEF

            Point 1: Imputation budgetaire - Observation: Conforme
            Point 2: Exactitude des calculs - Observation: Conforme
            Point 3: Conformite fiscale - Observation: NA
        """.trimIndent()
        assertEquals(TypeDocument.TABLEAU_CONTROLE, service.classify(text))
    }

    @Test
    fun `attestation fiscale DGI est classee ATTESTATION_FISCALE`() {
        val text = """
            Attestation de regularite fiscale
            Direction Generale des Impots
            ICE: 001234567000089
            Le contribuable est en situation reguliere.
        """.trimIndent()
        assertEquals(TypeDocument.ATTESTATION_FISCALE, service.classify(text))
    }

    @Test
    fun `checklist autocontrole CCF-EN-04 est classee CHECKLIST_AUTOCONTROLE`() {
        val text = """
            CCF-EN-04-V02 Check-list d autocontrole
            Point 1: Concordance facture / BC - OUI
            Point 2: Verification arithmetique - OUI
        """.trimIndent()
        assertEquals(TypeDocument.CHECKLIST_AUTOCONTROLE, service.classify(text))
    }

    @Test
    fun `PV de reception est classe PV_RECEPTION`() {
        val text = """
            Proces-verbal de reception
            Date: 15/03/2026
            Prestations recues: entretien mensuel
        """.trimIndent()
        assertEquals(TypeDocument.PV_RECEPTION, service.classify(text))
    }

    // --- Chemin LLM (keywords ont rate) ---

    @Test
    fun `LLM tool_use succes renvoie le type dans le bon format`() {
        val fake = FakeLlm(
            scripted = listOf(
                LlmOutcome.Ok("FORMULAIRE_FOURNISSEUR", 0.92)
            )
        )
        val svc = ClassificationService(fake)
        assertEquals(TypeDocument.FORMULAIRE_FOURNISSEUR, svc.classify(ambiguousText))
        assertEquals(1, fake.callsTotal())
        assertEquals(0, fake.overrideCalls)
    }

    @Test
    fun `LLM confidence basse au 1er essai declenche un retry avec override`() {
        val fake = FakeLlm(
            scripted = listOf(
                LlmOutcome.Ok("MARCHE", 0.45),
                LlmOutcome.Ok("MARCHE", 0.88)
            )
        )
        val svc = ClassificationService(fake)
        assertEquals(TypeDocument.MARCHE, svc.classify(ambiguousText))
        assertEquals(2, fake.callsTotal())
        assertEquals(1, fake.overrideCalls)
        assertTrue(fake.maxTokensSeen.contains(1024), "retry doit passer max_tokens=1024")
    }

    @Test
    fun `LLM exception au 1er essai declenche un retry qui peut reussir`() {
        val fake = FakeLlm(
            scripted = listOf(
                LlmOutcome.Throw(RuntimeException("No tool_use block in response. stop_reason=max_tokens")),
                LlmOutcome.Ok("CONTRAT_CADRE", 0.9)
            )
        )
        val svc = ClassificationService(fake)
        assertEquals(TypeDocument.CONTRAT_CADRE, svc.classify(ambiguousText))
        assertEquals(2, fake.callsTotal())
        assertEquals(1, fake.overrideCalls)
    }

    @Test
    fun `LLM categorie hors enum au 1er essai declenche retry`() {
        val fake = FakeLlm(
            scripted = listOf(
                LlmOutcome.Ok("QUELQUE_CHOSE_DE_BIZARRE", 0.8),
                LlmOutcome.Ok("PV_RECEPTION", 0.9)
            )
        )
        val svc = ClassificationService(fake)
        assertEquals(TypeDocument.PV_RECEPTION, svc.classify(ambiguousText))
        assertEquals(2, fake.callsTotal())
    }

    @Test
    fun `deux echecs consecutifs tombent sur INCONNU apres retry`() {
        val fake = FakeLlm(
            scripted = listOf(
                LlmOutcome.Throw(RuntimeException("network timeout")),
                LlmOutcome.Ok("FACTURE", 0.3)
            )
        )
        val svc = ClassificationService(fake)
        assertEquals(TypeDocument.INCONNU, svc.classify(ambiguousText))
        assertEquals(2, fake.callsTotal())
    }

    @Test
    fun `un seul retry est declenche au maximum`() {
        val fake = FakeLlm(
            scripted = listOf(
                LlmOutcome.Throw(RuntimeException("rate limit")),
                LlmOutcome.Throw(RuntimeException("overload")),
                LlmOutcome.Ok("FACTURE", 0.95)
            )
        )
        val svc = ClassificationService(fake)
        assertEquals(TypeDocument.INCONNU, svc.classify(ambiguousText))
        assertEquals(2, fake.callsTotal(), "pas plus de 2 appels LLM au total")
    }

    // --- Test double ---

    private sealed interface LlmOutcome {
        data class Ok(val categorie: String, val confidence: Double) : LlmOutcome
        data class Throw(val t: Throwable) : LlmOutcome
    }

    private class FakeLlm(
        private val scripted: List<LlmOutcome>
    ) : LlmExtractionService(
        ObjectMapper(),
        mock(AppSettingsService::class.java),
        mock(ClaudeUsageRepository::class.java)
    ) {
        var baselineCalls = 0
        var overrideCalls = 0
        val maxTokensSeen = mutableListOf<Int>()

        fun callsTotal(): Int = baselineCalls + overrideCalls

        private fun nextOutcome(): LlmOutcome {
            val idx = callsTotal()
            require(idx < scripted.size) { "Script a epuise ses reponses (idx=$idx)" }
            return scripted[idx]
        }

        override fun callClaudeTool(
            systemPrompt: String,
            userContent: String,
            toolName: String,
            inputSchema: Map<String, Any>,
            kind: CallKind
        ): ClaudeToolResponse {
            val outcome = nextOutcome()
            baselineCalls++
            return render(outcome, stopReason = "tool_use")
        }

        override fun callClaudeToolWithMaxTokens(
            systemPrompt: String,
            userContent: String,
            toolName: String,
            inputSchema: Map<String, Any>,
            kind: CallKind,
            maxTokensOverride: Int
        ): ClaudeToolResponse {
            val outcome = nextOutcome()
            overrideCalls++
            maxTokensSeen += maxTokensOverride
            return render(outcome, stopReason = "tool_use")
        }

        private fun render(outcome: LlmOutcome, stopReason: String): ClaudeToolResponse = when (outcome) {
            is LlmOutcome.Ok -> ClaudeToolResponse(
                toolInput = mapOf("categorie" to outcome.categorie, "confidence" to outcome.confidence),
                stopReason = stopReason
            )
            is LlmOutcome.Throw -> throw outcome.t
        }
    }
}
