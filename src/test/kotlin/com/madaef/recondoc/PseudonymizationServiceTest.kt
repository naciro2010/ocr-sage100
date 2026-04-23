package com.madaef.recondoc

import com.madaef.recondoc.service.extraction.PseudonymizationService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PseudonymizationServiceTest {

    private val service = PseudonymizationService()

    @Test
    fun `masque les emails`() {
        val (masked, mapping) = service.tokenize(
            "Contact : contact@acme.ma, facturation : compta@acme.ma"
        )
        assertFalse(masked.contains("contact@acme.ma"))
        assertFalse(masked.contains("compta@acme.ma"))
        assertTrue(masked.contains("[EMAIL_1]"))
        assertTrue(masked.contains("[EMAIL_2]"))
        assertEquals(2, mapping.backward.size)
    }

    @Test
    fun `token stable pour la meme valeur vue plusieurs fois`() {
        val (masked, mapping) = service.tokenize(
            "Email : jean.dupont@acme.ma. Relance a jean.dupont@acme.ma pour paiement."
        )
        assertEquals(1, mapping.backward.size)
        assertEquals(2, Regex("\\[EMAIL_1]").findAll(masked).count())
    }

    @Test
    fun `masque les telephones Maroc formats courants`() {
        val (masked, mapping) = service.tokenize(
            """
            Tel : +212 522 12 34 56
            Mobile : 0612345678
            Bureau : +212-5-22-98-76-54
            """.trimIndent()
        )
        assertEquals(3, mapping.backward.size, "3 telephones distincts")
        assertFalse(masked.contains("522 12 34 56"))
        assertFalse(masked.contains("0612345678"))
        assertTrue(masked.contains("[PHONE_1]"))
        assertTrue(masked.contains("[PHONE_2]"))
        assertTrue(masked.contains("[PHONE_3]"))
    }

    @Test
    fun `masque les RIB 24 chiffres`() {
        val (masked, mapping) = service.tokenize(
            "RIB : 007810000112345678901234. Autre RIB : 011 780 0001234567890123 45"
        )
        assertTrue(masked.contains("[RIB_1]"))
        assertTrue(masked.contains("[RIB_2]"))
        assertFalse(masked.contains("007810000112345678901234"))
        assertEquals(2, mapping.backward.size)
    }

    @Test
    fun `ne masque pas un ICE de 15 chiffres`() {
        val (masked, mapping) = service.tokenize("ICE : 001234567000089")
        assertEquals(0, mapping.backward.size)
        assertTrue(masked.contains("001234567000089"))
    }

    @Test
    fun `ne masque pas un IF court 8 chiffres`() {
        val (masked, mapping) = service.tokenize("IF : 12345678")
        assertEquals(0, mapping.backward.size)
        assertTrue(masked.contains("12345678"))
    }

    @Test
    fun `ne masque pas les montants ni les numeros de factures`() {
        val (masked, mapping) = service.tokenize(
            "Facture F-2026-0142 Montant TTC : 12 000,00 MAD. Total : 120000."
        )
        assertEquals(0, mapping.backward.size)
        assertTrue(masked.contains("F-2026-0142"))
        assertTrue(masked.contains("12 000,00"))
    }

    @Test
    fun `masque les noms precedes d'une civilite`() {
        val (masked, mapping) = service.tokenize(
            "Signe par M. Karim El-Amrani le 12/03/2026. Approuve par Mme Fatima Zahra."
        )
        assertFalse(masked.contains("Karim"))
        assertFalse(masked.contains("Fatima"))
        assertTrue(masked.contains("[PERSON_1]"))
        assertTrue(masked.contains("[PERSON_2]"))
        assertEquals(2, mapping.backward.size)
    }

    @Test
    fun `detokenize round-trip sur chaine`() {
        val original = "Contact : jean@acme.ma / tel +212 612 34 56 78"
        val (masked, mapping) = service.tokenize(original)
        val restored = service.detokenize(masked, mapping)
        assertEquals(original, restored)
    }

    @Test
    fun `detokenize sur Map imbriquee avec lignes`() {
        val (masked, mapping) = service.tokenize(
            "Email fournisseur : contact@acme.ma. Signataire : M. Jean Dupont."
        )
        val tokenEmail = mapping.forward["contact@acme.ma"]!!
        val tokenPerson = mapping.forward.keys.first { it.startsWith("M. Jean") }
            .let { mapping.forward[it]!! }
        val extracted: Map<String, Any?> = mapOf(
            "fournisseur" to "ACME SARL",
            "emailContact" to tokenEmail,
            "signataire" to tokenPerson,
            "lignes" to listOf(
                mapOf("designation" to "Prestation de M. Jean Dupont", "montant" to 1000),
                mapOf("designation" to "Relance $tokenEmail", "montant" to 500)
            )
        )
        @Suppress("UNCHECKED_CAST")
        val restored = service.detokenize(extracted, mapping) as Map<String, Any?>

        assertEquals("contact@acme.ma", restored["emailContact"])
        assertEquals("M. Jean Dupont", restored["signataire"])
        assertEquals("ACME SARL", restored["fournisseur"])
        @Suppress("UNCHECKED_CAST")
        val lignes = restored["lignes"] as List<Map<String, Any?>>
        assertEquals("Prestation de M. Jean Dupont", lignes[0]["designation"])
        assertEquals("Relance contact@acme.ma", lignes[1]["designation"])
    }

    @Test
    fun `tokenizeWith fusionne avec un mapping existant et garde les memes tokens`() {
        val (_, m1) = service.tokenize(
            "Fournisseur : contact@acme.ma - M. Karim El-Amrani"
        )
        val (masked2, m2) = service.tokenizeWith(
            "Paiement demande par M. Karim El-Amrani pour contact@acme.ma",
            m1
        )
        assertEquals(m1.backward.size, m2.backward.size, "pas de nouveau token pour des PII deja vues")
        val tokenEmail = m1.forward["contact@acme.ma"]!!
        assertTrue(masked2.contains(tokenEmail))
    }

    @Test
    fun `tokenizeWith attribue de nouveaux indices apres ceux du mapping existant`() {
        val (_, m1) = service.tokenize("Email : first@acme.ma")
        val (masked2, m2) = service.tokenizeWith("Email : second@acme.ma", m1)
        assertTrue(m2.backward.containsKey("[EMAIL_1]"))
        assertTrue(m2.backward.containsKey("[EMAIL_2]"))
        assertTrue(masked2.contains("[EMAIL_2]"))
    }

    @Test
    fun `texte vide ou sans PII reste inchange`() {
        val (m1, map1) = service.tokenize("")
        assertEquals("", m1)
        assertTrue(map1.isEmpty)

        val plain = "Facture F-2026-01 avec ICE 001234567000089, montant 12000,00 MAD"
        val (m2, map2) = service.tokenize(plain)
        assertEquals(plain, m2)
        assertTrue(map2.isEmpty)
    }

    @Test
    fun `detokenize retourne null et valeurs non-string inchangees`() {
        val (_, mapping) = service.tokenize("Email : test@acme.ma")
        assertEquals(null, service.detokenize(null, mapping))
        assertEquals(42, service.detokenize(42, mapping))
        assertEquals(true, service.detokenize(true, mapping))
    }

    @Test
    fun `mapping backward permet la recherche token vers valeur reelle`() {
        val (_, mapping) = service.tokenize("Contact : a@b.ma et c@d.ma")
        val token1 = mapping.forward["a@b.ma"]
        assertNotNull(token1)
        assertEquals("a@b.ma", mapping.backward[token1])
    }
}
