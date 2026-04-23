package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.extraction.GroundingValidator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verrouille le comportement anti-hallucination :
 *  - une valeur extraite par Claude mais absente du texte OCR est strip a null
 *  - un champ critique absent (ex: ICE facture) invalide le resultat
 *  - les valeurs reellement presentes dans le texte passent, y compris avec
 *    des separateurs/espaces/tirets ou des confusions OCR O/0 l/1.
 */
class GroundingValidatorTest {

    private val validator = GroundingValidator()

    @Test
    fun `ICE present dans le texte OCR passe`() {
        val text = "ICE: 001 509 176 000 008\nFacture N F-2026-001\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "ice" to "001509176000008",
            "numeroFacture" to "F-2026-001"
        ), text)
        assertTrue(r.valid)
        assertEquals("001509176000008", r.cleanedData["ice"])
    }

    @Test
    fun `ICE hallucine absent du texte est strip et leve violation critique`() {
        // L'OCR ne contient QUE "ICE: 001509176000008" mais Claude renvoie un
        // autre ICE plausible (15 chiffres) : le grounding doit le detecter.
        val text = "ICE: 001509176000008\nFacture 2026-001\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "ice" to "999888777666555",
            "numeroFacture" to "2026-001"
        ), text)
        assertFalse(r.valid, "ICE hallucine = champ critique absent = invalide")
        assertNull(r.cleanedData["ice"], "valeur hallucinee doit etre strip a null")
        assertTrue(r.violations.any { it.field == "ice" && it.reason.contains("absente") })
    }

    @Test
    fun `RIB hallucine est strip mais non critique (warning seul)`() {
        val text = "Facture DEV-2026-42\nMontant TTC: 12000 DH\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "DEV-2026-42",
            "rib" to "123456789012345678901234"
        ), text)
        assertTrue(r.valid, "RIB manquant du texte est un warning, pas une violation critique")
        assertNull(r.cleanedData["rib"], "RIB hallucine doit etre strip a null")
    }

    @Test
    fun `ICE avec confusion OCR O a la place de 0 dans la sortie Claude est groundé`() {
        // Claude a renvoye "OO1509176OOOOO8" (confusion OCR), OcrConfusions
        // normalise a "001509176000008" avant comparaison avec le texte.
        val text = "ICE 001509176000008 - ACME SARL"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "ice" to "OO1509176OOOOO8",
            "numeroFacture" to "F-1234"
        ), text)
        assertTrue(r.violations.none { it.field == "ice" })
    }

    @Test
    fun `numero facture halluciné est detecté`() {
        val text = "FACTURE DEV-2026-0042\nTotal TTC 12000\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "ice" to "001509176000008", // ice absent du texte aussi, mais on teste numeroFacture
            "numeroFacture" to "FAKE-9999"
        ), text)
        assertNull(r.cleanedData["numeroFacture"])
        assertTrue(r.violations.any { it.field == "numeroFacture" })
    }

    @Test
    fun `reference BC presente dans le texte OCR (avec espaces et tirets) passe`() {
        val text = "Bon de commande CF-SIE 2026/1234\nFournisseur ACME\n"
        val r = validator.validate(TypeDocument.BON_COMMANDE, mapOf(
            "reference" to "CF SIE 2026-1234"
        ), text)
        assertTrue(r.valid)
        assertNotNull(r.cleanedData["reference"])
    }

    @Test
    fun `OP halluciné est detecte sur le numero OP`() {
        val text = "ORDRE DE PAIEMENT OP-2026-0042\nBeneficiaire: ACME\n"
        val r = validator.validate(TypeDocument.ORDRE_PAIEMENT, mapOf(
            "numeroOp" to "OP-2099-9999",
            "rib" to "000000000000000000000000"
        ), text)
        assertFalse(r.valid, "numeroOp critique absent = invalide")
        assertNull(r.cleanedData["numeroOp"])
    }

    @Test
    fun `ICE absent volontairement (null) ne leve pas de violation`() {
        val text = "FACTURE DEV-2026-42\nTTC 12000\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "ice" to null,
            "numeroFacture" to "DEV-2026-42"
        ), text)
        assertTrue(r.valid, "null n'est pas une hallucination")
    }

    @Test
    fun `attestation avec numero present passe meme avec texte bruite`() {
        val text = """
            DIRECTION GENERALE DES IMPOTS
            ATTESTATION DE REGULARITE FISCALE
            N 2140/2026/798
            ICE : 001234567000089
        """.trimIndent()
        val r = validator.validate(TypeDocument.ATTESTATION_FISCALE, mapOf(
            "numero" to "2140-2026-798",
            "ice" to "001234567000089"
        ), text)
        assertTrue(r.valid)
    }

    // --- Couche engagement : MARCHE, BC_CADRE, CONTRAT_CADRE (decret 2-12-349) ---

    @Test
    fun `MARCHE reference hallucinée est detectee et strip`() {
        val text = """
            MARCHE DE TRAVAUX N M-2024-001
            Objet : Travaux d'entretien du golf royal
            Titulaire : ACME BTP SARL
            Appel d'offres : AO 2024/15
        """.trimIndent()
        val r = validator.validate(TypeDocument.MARCHE, mapOf(
            "reference" to "M-2099-FAKE",
            "numeroAo" to "AO 2024/15"
        ), text)
        assertFalse(r.valid, "reference marche critique absente = invalide")
        assertNull(r.cleanedData["reference"])
        assertNotNull(r.cleanedData["numeroAo"], "numeroAo present dans le texte ne doit pas etre strip")
    }

    @Test
    fun `BON_COMMANDE_CADRE reference presente est acceptee meme avec separateurs`() {
        val text = "BC CADRE BCC-2024-001\nPlafond 500 000 MAD HT\n"
        val r = validator.validate(TypeDocument.BON_COMMANDE_CADRE, mapOf(
            "reference" to "BCC 2024 001"
        ), text)
        assertTrue(r.valid)
        assertNotNull(r.cleanedData["reference"])
    }

    @Test
    fun `CONTRAT_CADRE avec reference absente du texte est invalide`() {
        val text = "Contrat de maintenance climatisation\nDuree : 24 mois\n"
        val r = validator.validate(TypeDocument.CONTRAT_CADRE, mapOf(
            "reference" to "CM-2024-015"
        ), text)
        assertFalse(r.valid, "reference contrat critique absente = invalide")
    }

    @Test
    fun `warnings precedents preserves et nouvelles violations concatenees`() {
        val text = "Facture DEV-2026-42\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "ice" to "999888777666555",
            "numeroFacture" to "DEV-2026-42",
            "_warnings" to listOf("OCR confiance basse sur page 2")
        ), text)
        val warnings = r.cleanedData["_warnings"] as? List<*>
        assertNotNull(warnings)
        assertEquals(2, warnings!!.size, "warning precedent + violation grounding")
        assertTrue(warnings.any { it.toString().contains("Grounding violation") })
    }
}
