package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.extraction.ExtractionSchemas
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifie la coherence structurelle des schemas tool_use Anthropic :
 * - chaque type de document pertinent a un schema
 * - les `required` sont bien declares dans les `properties`
 * - les cles matchent ce que MandatoryFields attend (contrat stable entre
 *   le schema envoye a Claude et le scoring qualite qui lit la reponse)
 * - les champs qualite (_confidence, _warnings) sont presents sur tous
 */
class ExtractionSchemasTest {

    @Test
    fun `schema disponible pour tous les types de document metier`() {
        val typesRequis = listOf(
            TypeDocument.FACTURE,
            TypeDocument.BON_COMMANDE,
            TypeDocument.ORDRE_PAIEMENT,
            TypeDocument.CONTRAT_AVENANT,
            TypeDocument.PV_RECEPTION,
            TypeDocument.ATTESTATION_FISCALE,
            TypeDocument.CHECKLIST_AUTOCONTROLE,
            TypeDocument.CHECKLIST_PIECES,
            TypeDocument.TABLEAU_CONTROLE
        )
        for (t in typesRequis) {
            assertNotNull(ExtractionSchemas.forType(t), "Aucun schema defini pour $t")
        }
    }

    @Test
    fun `INCONNU et FORMULAIRE_FOURNISSEUR n'ont pas de schema (OK)`() {
        assertNull(ExtractionSchemas.forType(TypeDocument.INCONNU))
        assertNull(ExtractionSchemas.forType(TypeDocument.FORMULAIRE_FOURNISSEUR))
    }

    @Test
    fun `chaque required est bien declare dans properties`() {
        for (t in TypeDocument.entries) {
            val schema = ExtractionSchemas.forType(t) ?: continue
            @Suppress("UNCHECKED_CAST")
            val properties = schema.inputSchema["properties"] as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val required = schema.inputSchema["required"] as? List<String> ?: emptyList()
            for (r in required) {
                assertTrue(r in properties.keys,
                    "Schema ${schema.name}: '$r' est required mais absent de properties")
            }
        }
    }

    @Test
    fun `tous les schemas exposent _confidence et _warnings`() {
        for (t in TypeDocument.entries) {
            val schema = ExtractionSchemas.forType(t) ?: continue
            @Suppress("UNCHECKED_CAST")
            val properties = schema.inputSchema["properties"] as Map<String, Any>
            assertTrue("_confidence" in properties.keys,
                "Schema ${schema.name}: _confidence manquant")
            assertTrue("_warnings" in properties.keys,
                "Schema ${schema.name}: _warnings manquant")
        }
    }

    @Test
    fun `additionalProperties false pour ne pas tolerer de cle hors schema`() {
        for (t in TypeDocument.entries) {
            val schema = ExtractionSchemas.forType(t) ?: continue
            assertEquals(false, schema.inputSchema["additionalProperties"],
                "Schema ${schema.name} doit refuser les proprietes hors schema")
        }
    }

    @Test
    fun `FACTURE force les cles alignees avec MandatoryFields`() {
        val schema = ExtractionSchemas.forType(TypeDocument.FACTURE)!!
        @Suppress("UNCHECKED_CAST")
        val required = schema.inputSchema["required"] as List<String>
        // Les cles mandatory cote ExtractionQualityService (PR #1)
        for (k in listOf("numeroFacture", "dateFacture", "montantTTC", "fournisseur")) {
            assertTrue(k in required, "FACTURE schema: '$k' doit etre required")
        }
    }

    @Test
    fun `BC force reference et dateBc (alignes avec les prompts)`() {
        val schema = ExtractionSchemas.forType(TypeDocument.BON_COMMANDE)!!
        @Suppress("UNCHECKED_CAST")
        val required = schema.inputSchema["required"] as List<String>
        assertTrue("reference" in required)
        assertTrue("dateBc" in required)
    }

    @Test
    fun `OP force numeroOp et dateEmission (alignes avec les prompts)`() {
        val schema = ExtractionSchemas.forType(TypeDocument.ORDRE_PAIEMENT)!!
        @Suppress("UNCHECKED_CAST")
        val required = schema.inputSchema["required"] as List<String>
        assertTrue("numeroOp" in required)
        assertTrue("dateEmission" in required)
    }

    @Test
    fun `ATTESTATION force numero dateEdition raisonSociale (pas dateValidite qui n'existe pas)`() {
        val schema = ExtractionSchemas.forType(TypeDocument.ATTESTATION_FISCALE)!!
        @Suppress("UNCHECKED_CAST")
        val required = schema.inputSchema["required"] as List<String>
        assertTrue("numero" in required)
        assertTrue("dateEdition" in required)
        assertTrue("raisonSociale" in required)
        // dateValidite et fournisseur ne sont PAS dans le prompt, donc pas dans le schema
        @Suppress("UNCHECKED_CAST")
        val properties = schema.inputSchema["properties"] as Map<String, Any>
        assertTrue("dateValidite" !in properties.keys,
            "dateValidite n'existe pas dans le prompt ATTESTATION_FISCALE")
    }

    @Test
    fun `noms d'outils sont uniques et stables (extract_type_data)`() {
        val names = TypeDocument.entries.mapNotNull { ExtractionSchemas.forType(it)?.name }
        assertEquals(names.size, names.toSet().size, "Les noms d'outils doivent etre uniques")
        for (n in names) {
            assertTrue(n.startsWith("extract_") && n.endsWith("_data"),
                "Nom d'outil '$n' doit suivre le pattern extract_<type>_data")
        }
    }
}
