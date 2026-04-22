package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.extraction.ClassificationService
import com.madaef.recondoc.service.extraction.LlmExtractionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals

/**
 * Couvre uniquement le chemin keyword-based (rapide, 0 appel LLM). Les tests
 * construisent des textes realistes pour verrouiller la branche anti-confusion
 * FACTURE vs BON_COMMANDE et quelques classifications canoniques.
 */
class ClassificationServiceTest {

    private val llm = mock(LlmExtractionService::class.java)
    private val service = ClassificationService(llm)

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
}
