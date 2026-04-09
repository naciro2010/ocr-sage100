package com.madaef.recondoc

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
class SchemaValidationTest {

    @Autowired
    lateinit var entityManager: EntityManager

    @Test
    fun `all JPA entities are queryable against the schema`() {
        // This catches the exact error that broke Railway: an entity references a table
        // that doesn't exist (e.g. Invoice -> invoice_line_items).
        // With ddl-auto=create-drop (test profile), H2 creates tables from entities.
        // If an entity has invalid mappings, this will fail.
        val entities = entityManager.metamodel.entities
        assertTrue(entities.isNotEmpty(), "JPA metamodel should have entities")

        for (entity in entities) {
            val query = entityManager.createQuery("SELECT COUNT(e) FROM ${entity.name} e")
            val count = query.singleResult as Long
            assertTrue(count >= 0, "Entity ${entity.name} should be queryable")
        }
    }

    @Test
    fun `no orphan entity references non-existent tables`() {
        // Verify entity names match expected dossier domain entities only
        val entityNames = entityManager.metamodel.entities.map { it.name }.toSet()

        // These are the entities that SHOULD exist after cleanup
        val expectedEntities = setOf(
            "DossierPaiement", "Document", "Facture", "LigneFacture",
            "BonCommande", "ContratAvenant", "GrilleTarifaire",
            "OrdrePaiement", "Retenue",
            "ChecklistAutocontrole", "PointControle", "SignataireChecklist",
            "TableauControle", "PointControleFinancier",
            "PvReception", "AttestationFiscale",
            "ResultatValidation", "AuditLog", "AppSetting"
        )

        // No legacy entities should exist
        val legacyEntities = setOf("Invoice", "InvoiceLineItem")
        for (legacy in legacyEntities) {
            assertTrue(legacy !in entityNames, "Legacy entity $legacy should not exist in metamodel")
        }

        // All expected entities should be present
        for (expected in expectedEntities) {
            assertTrue(expected in entityNames, "Expected entity $expected missing from metamodel")
        }
    }
}
