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
    fun `RIB hybride - 20 derniers chiffres composes de 2 RIBs distincts est detecte`() {
        // Cas pathologique : le texte OCR liste 2 RIBs distincts. Claude
        // hybride (debut RIB-A + fin RIB-B) en gardant les 12 derniers chiffres
        // qui correspondent au RIB-B. Avant le fix (minLen=12), ce RIB hybride
        // passait le grounding car les 12 derniers chiffres existaient.
        // Avec minLen=20, l'hybride est detecte (les 20 derniers chiffres ne
        // correspondent a aucun RIB pur).
        val ribA = "230810000150002775637823"  // 24 digits
        val ribB = "001780000220003344556677"  // 24 digits
        val ribHybride = ribA.substring(0, 12) + ribB.substring(12)  // 12 debut A + 12 fin B
        val text = "RIB principal: $ribA\nRIB secondaire (info): $ribB\nFacture F-2026-001\n"

        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-2026-001",
            "rib" to ribHybride
        ), text)
        assertNull(r.cleanedData["rib"], "RIB hybride doit etre strip a null")
        assertTrue(r.violations.any { it.field == "rib" && it.reason.contains("absente") })
    }

    @Test
    fun `RIB legitime present dans le texte passe meme avec espaces et tirets`() {
        val rib = "230810000150002775637823"
        val text = "RIB beneficiaire : 230 810 000150002775 6378 23\nFacture F-2026-001\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-2026-001",
            "rib" to rib
        ), text)
        assertTrue(r.valid)
        assertEquals(rib, r.cleanedData["rib"])
    }

    // --- Citations source_quote (anti-hallucination renforcee) ---

    @Test
    fun `citation source presente dans le texte OCR valide le champ`() {
        val text = "FACTURE N F-2026-001\nDate : 15/03/2026\nICE : 001 509 176 000 008\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "ice" to "001509176000008",
            "numeroFacture" to "F-2026-001",
            "_sourceQuotes" to listOf(
                mapOf("field" to "ice", "quote" to "ICE : 001 509 176 000 008"),
                mapOf("field" to "numeroFacture", "quote" to "FACTURE N F-2026-001")
            )
        ), text)
        assertTrue(r.valid)
        assertEquals("001509176000008", r.cleanedData["ice"])
        assertEquals("F-2026-001", r.cleanedData["numeroFacture"])
    }

    @Test
    fun `citation absente du texte strip la valeur extraite (hallucination cite)`() {
        // Claude renvoie une citation qui n'apparait NULLE PART dans le texte OCR
        // -> hallucination probable, on strip le champ.
        val text = "FACTURE N F-2026-001\nMontant TTC : 12 000,00 DH\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-2026-001",
            "ice" to "001509176000008",
            "_sourceQuotes" to listOf(
                mapOf("field" to "ice", "quote" to "ICE attribue par DGI: 001509176000008")
            )
        ), text)
        assertNull(r.cleanedData["ice"], "ICE avec citation introuvable doit etre strip")
        assertFalse(r.valid, "ICE est critique sur FACTURE")
        assertTrue(r.violations.any { it.field == "ice" && it.reason.contains("citation") })
    }

    @Test
    fun `champ critique extrait sans citation est rejete (preuve manquante)`() {
        // Claude oublie de citer ses sources sur un champ critique : on durcit.
        // Sans citation, on ne peut pas distinguer une lecture honnete d'une
        // hallucination -> traiter comme suspect.
        val text = "FACTURE N F-2026-001\nICE : 001509176000008\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-2026-001",
            "ice" to "001509176000008",
            "_sourceQuotes" to listOf(
                // Citation fournie SEULEMENT pour numeroFacture, pas pour ice
                mapOf("field" to "numeroFacture", "quote" to "FACTURE N F-2026-001")
            )
        ), text)
        assertNull(r.cleanedData["ice"], "ICE sans citation doit etre strip")
        assertFalse(r.valid)
        assertTrue(r.violations.any { it.field == "ice" && it.reason.contains("preuve manquante") })
    }

    @Test
    fun `tolerance OCR de 1 caractere sur la citation (confusion O 0)`() {
        // Citation contient OO au lieu de 00 (confusion OCR frequente). Avec
        // une fenetre fuzzy de 1 caractere, la citation reste valide.
        val text = "FACTURE N F-2026-001\nICE : 001509176000008\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-2026-001",
            "ice" to "001509176000008",
            "_sourceQuotes" to listOf(
                mapOf("field" to "numeroFacture", "quote" to "FACTURE N F-2026-001"),
                mapOf("field" to "ice", "quote" to "ICE : 0O1509176000008") // O au lieu de 0 a la 2e position
            )
        ), text)
        assertTrue(r.violations.none { it.field == "ice" && it.reason.contains("citation") },
            "1 caractere de difference doit passer la fuzz")
    }

    @Test
    fun `absence totale de _sourceQuotes preserve le comportement legacy`() {
        // Si Claude ne fournit pas du tout de _sourceQuotes (compat ascendante,
        // cache stale, vieux prompt), on retombe sur le grounding par valeur.
        val text = "FACTURE N F-2026-001\nICE : 001509176000008\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-2026-001",
            "ice" to "001509176000008"
            // _sourceQuotes absent
        ), text)
        assertTrue(r.valid, "absence de _sourceQuotes ne doit pas casser l'extraction legacy")
    }

    @Test
    fun `citation valide avec separateurs differents (espaces tirets)`() {
        // OCR : "ICE: 001-509-176-000-008", citation : "ICE : 001 509 176 000 008"
        // Apres normalisation (suppression espaces/tirets/ponctuation), les deux
        // donnent "ice001509176000008" -> match.
        val text = "FACTURE N F-2026-001\nICE: 001-509-176-000-008\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-2026-001",
            "ice" to "001509176000008",
            "_sourceQuotes" to listOf(
                mapOf("field" to "numeroFacture", "quote" to "FACTURE N F-2026-001"),
                mapOf("field" to "ice", "quote" to "ICE : 001 509 176 000 008")
            )
        ), text)
        assertTrue(r.violations.none { it.field == "ice" })
    }

    @Test
    fun `citation pour champ non extrait (null) est ignoree silencieusement`() {
        // Edge case : Claude fournit une citation pour un champ qu'il a mis a
        // null. Pas d'erreur, on ignore.
        val text = "FACTURE N F-2026-001\n"
        val r = validator.validate(TypeDocument.FACTURE, mapOf(
            "numeroFacture" to "F-2026-001",
            "ice" to null,
            "_sourceQuotes" to listOf(
                mapOf("field" to "numeroFacture", "quote" to "FACTURE N F-2026-001"),
                mapOf("field" to "ice", "quote" to "ICE : non lu")
            )
        ), text)
        assertTrue(r.valid)
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
