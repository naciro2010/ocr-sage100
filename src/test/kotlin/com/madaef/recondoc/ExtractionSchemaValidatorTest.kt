package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.extraction.ExtractionSchemaValidator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractionSchemaValidatorTest {

    private val validator = ExtractionSchemaValidator()

    @Test
    fun `facture valide passe sans violation`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-2026-001",
            "dateFacture" to "2026-03-15",
            "fournisseur" to "ACME",
            "ice" to "001509176000008",
            "montantTTC" to 1200.0,
            "montantHT" to 1000.0,
            "montantTVA" to 200.0,
            "tauxTVA" to 20
        ))
        assertTrue(r.valid)
        assertEquals(0, r.violations.size)
    }

    @Test
    fun `ICE non conforme est strip et donne violation critique`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "X", "ice" to "123", "montantTTC" to 100
        ))
        assertFalse(r.valid, "ICE critique invalide doit rendre result invalide")
        assertNull(r.cleanedData["ice"], "ICE critique invalide doit etre strip a null")
        val warnings = r.cleanedData["_warnings"] as? List<*>
        assertTrue(warnings != null && warnings.any { it.toString().contains("ice") })
    }

    @Test
    fun `RIB non conforme est strip mais non critique par defaut`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "ACME", "ice" to "001509176000008",
            "montantTTC" to 100, "rib" to "0000"
        ))
        assertTrue(r.valid, "RIB non critique invalide n'invalide pas le result")
        assertNull(r.cleanedData["rib"], "RIB invalide doit etre strip meme si non critique")
    }

    @Test
    fun `montant negatif detecte`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "X", "ice" to "001509176000008",
            "montantTTC" to -100
        ))
        assertFalse(r.valid)
        assertTrue(r.violations.any { it.field == "montantTTC" && it.reason.contains("negatif") })
    }

    @Test
    fun `taux TVA hors liste Maroc refuse`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "X", "ice" to "001509176000008",
            "montantTTC" to 100, "tauxTVA" to 18
        ))
        assertTrue(r.violations.any { it.field == "tauxTVA" })
    }

    @Test
    fun `taux TVA 20 pourcent accepte`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "X", "ice" to "001509176000008",
            "montantTTC" to 100, "tauxTVA" to 20
        ))
        assertTrue(r.violations.none { it.field == "tauxTVA" })
    }

    @Test
    fun `date dd-MM-yyyy acceptee`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "15/03/2026",
            "fournisseur" to "X", "ice" to "001509176000008",
            "montantTTC" to 100
        ))
        assertTrue(r.violations.none { it.field == "dateFacture" })
    }

    @Test
    fun `numeroFacture vide est violation NON_VIDE sans strip`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "", "dateFacture" to "2026-01-01",
            "fournisseur" to "X", "ice" to "001509176000008",
            "montantTTC" to 100
        ))
        assertFalse(r.valid)
        assertTrue(r.violations.any { it.field == "numeroFacture" && it.reason.contains("vide") })
        assertEquals("", r.cleanedData["numeroFacture"], "NON_VIDE ne strip pas la valeur originale")
    }

    @Test
    fun `ICE avec espaces internes est accepte apres nettoyage`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "ACME", "ice" to "001 509 176 000 008",
            "montantTTC" to 100
        ))
        assertTrue(r.violations.none { it.field == "ice" })
    }

    // --- Durcissement NON_VIDE : placeholders, ponctuation seule, strings trop courtes ---

    @Test
    fun `fournisseur N slash A refuse comme placeholder`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "N/A", "ice" to "001509176000008",
            "montantTTC" to 100
        ))
        assertFalse(r.valid)
        assertTrue(r.violations.any { it.field == "fournisseur" && it.reason.contains("placeholder") })
    }

    @Test
    fun `fournisseur inconnu refuse comme placeholder`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "Inconnu", "ice" to "001509176000008",
            "montantTTC" to 100
        ))
        assertTrue(r.violations.any { it.field == "fournisseur" && it.reason.contains("placeholder") })
    }

    @Test
    fun `fournisseur point d'interrogation seul refuse`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "?", "ice" to "001509176000008",
            "montantTTC" to 100
        ))
        assertTrue(r.violations.any { it.field == "fournisseur" })
    }

    @Test
    fun `numeroFacture d'un seul caractere refuse (trop court)`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F", "dateFacture" to "2026-01-01",
            "fournisseur" to "ACME", "ice" to "001509176000008",
            "montantTTC" to 100
        ))
        assertTrue(r.violations.any { it.field == "numeroFacture" && it.reason.contains("trop courte") })
    }

    @Test
    fun `fournisseur compose uniquement de ponctuation refuse`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "---", "ice" to "001509176000008",
            "montantTTC" to 100
        ))
        assertTrue(r.violations.any { it.field == "fournisseur" })
    }

    @Test
    fun `fournisseur reel (longueur et contenu) accepte`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "ACME SARL", "ice" to "001509176000008",
            "montantTTC" to 100
        ))
        assertTrue(r.violations.none { it.field == "fournisseur" })
    }

    @Test
    fun `placeholder insensible a la casse (NA, Na, na) refuse`() {
        for (v in listOf("NA", "Na", "na", "n.a.", "NONE", "TBD")) {
            val r = validator.validate(TypeDocument.FACTURE, mapOf(
                "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
                "fournisseur" to v, "ice" to "001509176000008",
                "montantTTC" to 100
            ))
            assertTrue(r.violations.any { it.field == "fournisseur" },
                "'$v' devrait etre refuse comme placeholder")
        }
    }

    // --- Normalisation OCR des champs 100% numeriques (O->0, l->1) ---

    @Test
    fun `ICE avec O (lettre) a la place de 0 (chiffre) est corrige`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "ACME",
            "ice" to "OO15O9176OOOOO8", // O a la place des 0
            "montantTTC" to 100
        ))
        assertTrue(r.violations.none { it.field == "ice" },
            "ICE avec O OCR doit etre corrige en 0 et accepte")
    }

    @Test
    fun `ICE avec l (lettre minuscule) a la place de 1 est corrige`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "ACME",
            "ice" to "00l509l76000008", // l a la place des 1
            "montantTTC" to 100
        ))
        assertTrue(r.violations.none { it.field == "ice" },
            "ICE avec l OCR doit etre corrige en 1 et accepte")
    }

    @Test
    fun `ICE avec I (lettre majuscule) a la place de 1 est corrige`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "ACME",
            "ice" to "00I509I76000008", // I a la place des 1
            "montantTTC" to 100
        ))
        assertTrue(r.violations.none { it.field == "ice" })
    }

    @Test
    fun `RIB avec espaces, tirets et O OCR est accepte apres normalisation`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "ACME", "ice" to "001509176000008",
            "montantTTC" to 100,
            "rib" to "O11 810-OOOOOO123456789012" // 24 chiffres avec O
        ))
        // RIB devient 011810000000123456789012 = 24 chiffres -> OK
        assertTrue(r.violations.none { it.field == "rib" })
        assertTrue(r.cleanedData["rib"] != null, "RIB valide ne doit pas etre strip")
    }

    @Test
    fun `ICE avec OCR cryptographique OO1 reste 001 (pas de strip arbitraire)`() {
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-1", "dateFacture" to "2026-01-01",
            "fournisseur" to "ACME",
            "ice" to "0012345670000O9", // seul le dernier 0 est un O
            "montantTTC" to 100
        ))
        assertTrue(r.violations.none { it.field == "ice" })
    }
}
