package com.madaef.recondoc

import com.madaef.recondoc.service.OcrSelectionService
import com.madaef.recondoc.service.OcrService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrSelectionServiceTest {

    private val service = OcrSelectionService()

    private fun candidate(
        text: String,
        engine: OcrService.OcrEngine = OcrService.OcrEngine.TIKA,
        pageCount: Int = 1,
        confidence: Double = 0.8
    ) = OcrSelectionService.Candidate(text, engine, pageCount, confidence)

    @Test
    fun `texte vide obtient score zero`() {
        assertEquals(0, service.score(candidate("")))
        assertEquals(0, service.score(candidate("   \n\t  ")))
    }

    @Test
    fun `candidat avec montants decimaux est mieux note qu'un texte brut`() {
        val noAmounts = candidate("Facture fournisseur prestations entretien mensuelle")
        val withAmounts = candidate("Facture ACME Montant HT 10 000,00 TVA 2 000,00 TTC 12 000,00")
        assertTrue(service.score(withAmounts) > service.score(noAmounts),
            "Les montants decimaux doivent augmenter le score")
    }

    @Test
    fun `candidat avec tableaux Markdown bat un Tika sans structure`() {
        val tikaFlat = candidate(
            text = "ACME SARL Facture 2026-0142 Montant HT 10 000 TVA 20 2 000 TTC 12 000",
            engine = OcrService.OcrEngine.TIKA
        )
        val mistralTable = candidate(
            text = """
                # Facture 2026-0142
                | Designation | HT | TVA | TTC |
                | --- | --- | --- | --- |
                | Prestation | 10000 | 2000 | 12000 |
            """.trimIndent(),
            engine = OcrService.OcrEngine.MISTRAL_OCR
        )
        val winner = service.pickBest(listOf(tikaFlat, mistralTable))
        assertNotNull(winner)
        assertEquals(OcrService.OcrEngine.MISTRAL_OCR, winner!!.engine,
            "Un tableau Markdown structure doit l'emporter sur du texte plat equivalent")
    }

    @Test
    fun `dates ISO et FR augmentent le score`() {
        val sans = candidate("Texte sans aucune date ecrite ici")
        val avec = candidate("Document emis le 2026-03-15 valide jusqu'au 15/09/2026")
        assertTrue(service.score(avec) > service.score(sans))
    }

    @Test
    fun `lignes courtes (bruit) penalisent le score`() {
        val propre = candidate("Facture ACME SARL prestation janvier 2026 montant ht 10000")
        val bruite = candidate("""
            Facture ACME SARL
            A
            B
            C
            .
            ,
            prestation janvier 2026 montant ht 10000
        """.trimIndent())
        assertTrue(service.score(propre) > service.score(bruite),
            "Les lignes 1-2 chars doivent etre penalisees comme bruit")
    }

    @Test
    fun `pickBest retourne null si tous les candidats sont vides`() {
        val best = service.pickBest(listOf(candidate(""), candidate("   ")))
        assertNull(best)
    }

    @Test
    fun `pickBest filtre les candidats vides`() {
        val empty = candidate("")
        val real = candidate("Facture ACME montant TTC 12 000,00 net a payer 12000")
        val best = service.pickBest(listOf(empty, real))
        assertEquals(real, best)
    }

    @Test
    fun `plus de mots = meilleur score a structure egale`() {
        val court = candidate("Facture ACME")
        val long = candidate("Facture ACME SARL prestations entretien mensuelle janvier 2026 reference contrat C-2026-001")
        assertTrue(service.score(long) > service.score(court))
    }

    @Test
    fun `PdfMarkdown avec tables bat Tesseract bruite`() {
        val tess = candidate(
            text = "Factur3 ACNE SARL mont0nt TVA 2000",
            engine = OcrService.OcrEngine.TESSERACT,
            confidence = 0.45
        )
        val md = candidate(
            text = """
                | Designation | HT | TTC |
                | --- | --- | --- |
                | Service | 10000 | 12000,00 |
            """.trimIndent(),
            engine = OcrService.OcrEngine.PDFBOX_MARKDOWN,
            confidence = 0.95
        )
        val winner = service.pickBest(listOf(tess, md))
        assertEquals(OcrService.OcrEngine.PDFBOX_MARKDOWN, winner?.engine)
    }
}
