package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.DossierType
import com.madaef.recondoc.entity.dossier.StatutDossier
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.extraction.ExtractionQualityService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractionQualityServiceTest {

    private val service = ExtractionQualityService()

    private fun buildDossier() = DossierPaiement(
        reference = "TEST-001",
        type = DossierType.BC,
        statut = StatutDossier.BROUILLON
    )

    private fun buildFacture(data: Map<String, Any?>, confOcr: Double = 90.0, confExtract: Double = 0.9): Document {
        val doc = Document(
            dossier = buildDossier(),
            typeDocument = TypeDocument.FACTURE,
            nomFichier = "f.pdf",
            cheminFichier = "/tmp/f.pdf"
        )
        doc.ocrConfidence = confOcr
        doc.extractionConfidence = confExtract
        doc.donneesExtraites = data
        return doc
    }

    @Test
    fun `facture complete gets high score`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-2026-001",
            "dateFacture" to "2026-03-15",
            "montantTTC" to 12000.0,
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "tauxTVA" to 20,
            "fournisseur" to "ACME SARL",
            "ice" to "123456789012345",
            "identifiantFiscal" to "IF001",
            "rib" to "0000000000000000",
            "lignes" to listOf(mapOf("designation" to "Service", "montantTotalHT" to 10000))
        ))
        val r = service.evaluate(doc)
        assertTrue(r.score >= 85, "score should be >= 85, got ${r.score}")
        assertEquals(emptyList(), r.missingMandatory)
        assertTrue(r.coherenceArithmetique >= 0.99, "arith HT+TVA=TTC should match")
    }

    @Test
    fun `facture without mandatory fields gets low score`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-1",
            "montantTTC" to 1000.0
        ), confOcr = 60.0, confExtract = 0.6)
        val r = service.evaluate(doc)
        assertTrue(r.score < 60, "score should be <60, got ${r.score}")
        assertTrue("dateFacture" in r.missingMandatory)
        assertTrue("fournisseur" in r.missingMandatory)
        assertTrue("ice" in r.missingMandatory)
    }

    @Test
    fun `arithmetic inconsistency drops coherence score`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-1",
            "dateFacture" to "2026-01-01",
            "montantTTC" to 15000.0,
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "fournisseur" to "X",
            "ice" to "1"
        ))
        val r = service.evaluate(doc)
        assertTrue(r.coherenceArithmetique < 1.0, "coherence should be <1 on HT+TVA != TTC")
    }

    @Test
    fun `blank string field counts as missing`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "",
            "dateFacture" to "2026-01-01",
            "montantTTC" to 1000.0,
            "fournisseur" to "X",
            "ice" to "1"
        ))
        val r = service.evaluate(doc)
        assertTrue("numeroFacture" in r.missingMandatory)
    }

    @Test
    fun `applyTo persists score and missing fields on document`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-1",
            "montantTTC" to 100.0
        ))
        service.applyTo(doc)
        assertTrue(doc.extractionQualityScore != null)
        assertTrue(doc.missingMandatoryFields != null)
        assertTrue(doc.missingMandatoryFields!!.contains("fournisseur"))
    }

    @Test
    fun `case insensitive field matching`() {
        val doc = buildFacture(mapOf(
            "NumeroFacture" to "F-1",
            "DateFacture" to "2026-01-01",
            "MontantTTC" to 100.0,
            "Fournisseur" to "X",
            "ICE" to "1"
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory)
    }
}
