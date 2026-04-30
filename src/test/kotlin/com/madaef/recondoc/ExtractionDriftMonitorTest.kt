package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.extraction.ExtractionDriftMonitor
import com.madaef.recondoc.service.extraction.ExtractionDriftMonitor.FieldOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verrouille le contrat des metriques de derive :
 *  - increments par (type, champ, outcome)
 *  - distribution confidence et quality_score
 *  - classification correcte des outcomes en fonction des snapshots inter-couches
 *  - aucune fuite de PII (tags = type/field/outcome uniquement)
 */
class ExtractionDriftMonitorTest {

    private fun newMonitor() = ExtractionDriftMonitor(SimpleMeterRegistry())

    @Test
    fun `recordFieldOutcome incremente le counter par tag`() {
        val m = newMonitor()
        m.recordFieldOutcome(TypeDocument.FACTURE, "ice", FieldOutcome.EXTRACTED)
        m.recordFieldOutcome(TypeDocument.FACTURE, "ice", FieldOutcome.EXTRACTED)
        m.recordFieldOutcome(TypeDocument.FACTURE, "ice", FieldOutcome.STRIPPED_GROUNDING)

        assertEquals(2L, m.countFor(TypeDocument.FACTURE, "ice", FieldOutcome.EXTRACTED))
        assertEquals(1L, m.countFor(TypeDocument.FACTURE, "ice", FieldOutcome.STRIPPED_GROUNDING))
        assertEquals(0L, m.countFor(TypeDocument.FACTURE, "ice", FieldOutcome.STRIPPED_COVE))
    }

    @Test
    fun `counters distincts entre types de documents (pas de leak)`() {
        val m = newMonitor()
        m.recordFieldOutcome(TypeDocument.FACTURE, "ice", FieldOutcome.EXTRACTED)
        m.recordFieldOutcome(TypeDocument.ATTESTATION_FISCALE, "ice", FieldOutcome.EXTRACTED)

        assertEquals(1L, m.countFor(TypeDocument.FACTURE, "ice", FieldOutcome.EXTRACTED))
        assertEquals(1L, m.countFor(TypeDocument.ATTESTATION_FISCALE, "ice", FieldOutcome.EXTRACTED))
    }

    @Test
    fun `recordConfidence accepte les valeurs valides et ignore les aberrantes`() {
        val registry = SimpleMeterRegistry()
        val m = ExtractionDriftMonitor(registry)
        m.recordConfidence(TypeDocument.FACTURE, 0.95)
        m.recordConfidence(TypeDocument.FACTURE, 0.50)
        m.recordConfidence(TypeDocument.FACTURE, 1.5)  // ignore
        m.recordConfidence(TypeDocument.FACTURE, -0.1) // ignore

        val summary = registry.find("recondoc.extraction.confidence")
            .tag("type", "FACTURE").summary()
        assertNotNull(summary)
        assertEquals(2L, summary.count(), "Seules les 2 valeurs valides sont enregistrees")
    }

    @Test
    fun `recordQualityScore accepte 0-100 et ignore le reste`() {
        val registry = SimpleMeterRegistry()
        val m = ExtractionDriftMonitor(registry)
        m.recordQualityScore(TypeDocument.FACTURE, 85)
        m.recordQualityScore(TypeDocument.FACTURE, 30)
        m.recordQualityScore(TypeDocument.FACTURE, 105) // ignore
        m.recordQualityScore(TypeDocument.FACTURE, -1)  // ignore

        val summary = registry.find("recondoc.extraction.quality_score")
            .tag("type", "FACTURE").summary()
        assertNotNull(summary)
        assertEquals(2L, summary.count())
    }

    @Test
    fun `recordDocumentOutcomes - extracted clean (passe toutes les couches)`() {
        val m = newMonitor()
        val same = mapOf<String, Any?>("ice" to "001509176000008", "numeroFacture" to "F-2026-001")
        m.recordDocumentOutcomes(
            TypeDocument.FACTURE,
            criticalFields = listOf("ice", "numeroFacture"),
            afterExtraction = same,
            afterGrounding = same,
            afterConsistency = same,
            afterReconciliation = same,
            afterCove = same
        )
        assertEquals(1L, m.countFor(TypeDocument.FACTURE, "ice", FieldOutcome.EXTRACTED))
        assertEquals(1L, m.countFor(TypeDocument.FACTURE, "numeroFacture", FieldOutcome.EXTRACTED))
    }

    @Test
    fun `recordDocumentOutcomes - null des le run 1 classifie en NULL_VALUE`() {
        val m = newMonitor()
        val data = mapOf<String, Any?>("ice" to null)
        m.recordDocumentOutcomes(
            TypeDocument.FACTURE,
            criticalFields = listOf("ice"),
            afterExtraction = data,
            afterGrounding = data,
            afterConsistency = data,
            afterReconciliation = data,
            afterCove = data
        )
        assertEquals(1L, m.countFor(TypeDocument.FACTURE, "ice", FieldOutcome.NULL_VALUE))
    }

    @Test
    fun `recordDocumentOutcomes - strip par grounding classifie en STRIPPED_GROUNDING`() {
        val m = newMonitor()
        val full = mapOf<String, Any?>("ice" to "001509176000008")
        val stripped = mapOf<String, Any?>("ice" to null)
        m.recordDocumentOutcomes(
            TypeDocument.FACTURE,
            criticalFields = listOf("ice"),
            afterExtraction = full,
            afterGrounding = stripped,
            afterConsistency = stripped,
            afterReconciliation = stripped,
            afterCove = stripped
        )
        assertEquals(1L, m.countFor(TypeDocument.FACTURE, "ice", FieldOutcome.STRIPPED_GROUNDING))
    }

    @Test
    fun `recordDocumentOutcomes - strip par CoVe classifie en STRIPPED_COVE`() {
        val m = newMonitor()
        val full = mapOf<String, Any?>("ice" to "001509176000008")
        val stripped = mapOf<String, Any?>("ice" to null)
        m.recordDocumentOutcomes(
            TypeDocument.FACTURE,
            criticalFields = listOf("ice"),
            afterExtraction = full,
            afterGrounding = full,
            afterConsistency = full,
            afterReconciliation = full,
            afterCove = stripped
        )
        assertEquals(1L, m.countFor(TypeDocument.FACTURE, "ice", FieldOutcome.STRIPPED_COVE))
    }

    @Test
    fun `recordDocumentOutcomes - reconcile classifie en RECONCILED`() {
        val m = newMonitor()
        val pre = mapOf<String, Any?>("montantTTC" to 12000.00)
        val post = mapOf<String, Any?>("montantTTC" to 12000.02) // arrondi corrige
        m.recordDocumentOutcomes(
            TypeDocument.FACTURE,
            criticalFields = listOf("montantTTC"),
            afterExtraction = pre,
            afterGrounding = pre,
            afterConsistency = pre,
            afterReconciliation = post,
            afterCove = post
        )
        assertEquals(1L, m.countFor(TypeDocument.FACTURE, "montantTTC", FieldOutcome.RECONCILED))
    }

    @Test
    fun `tags Prometheus sont sans PII (type field outcome uniquement)`() {
        val registry = SimpleMeterRegistry()
        val m = ExtractionDriftMonitor(registry)
        m.recordFieldOutcome(TypeDocument.FACTURE, "ice", FieldOutcome.EXTRACTED)

        val counter = registry.find("recondoc.extraction.field.outcome")
            .tag("type", "FACTURE").tag("field", "ice").tag("outcome", "extracted").counter()
        assertNotNull(counter)
        // Aucune valeur extraite ne doit apparaitre dans les tags
        val tagKeys = counter.id.tags.map { it.key }.toSet()
        assertTrue(tagKeys == setOf("type", "field", "outcome"),
            "Tags doivent etre exclusivement {type, field, outcome}, observe: $tagKeys")
    }
}
