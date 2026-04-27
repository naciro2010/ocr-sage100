package com.madaef.recondoc

import com.madaef.recondoc.service.extraction.LlmJsonUtils
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verrouille le scanner de premier objet JSON balanced utilise par
 * `DossierService.parseLlmResponse` quand Claude renvoie le JSON entoure
 * de texte explicatif.
 *
 * Bug historique : la version regex `\\{[\\s\\S]*\\}` etait greedy et
 * captait du PREMIER `{` jusqu'au DERNIER `}`, donc concatenait plusieurs
 * blocs JSON + le texte intermediaire, produisant un JSON invalide qui
 * faisait silencieusement perdre l'extraction.
 */
class LlmJsonUtilsTest {

    @Test
    fun `objet JSON simple - pris tel quel`() {
        val text = """{"a":1,"b":"x"}"""
        assertEquals("""{"a":1,"b":"x"}""", LlmJsonUtils.extractFirstJsonObject(text))
    }

    @Test
    fun `JSON entoure de texte - extrait uniquement le bloc JSON`() {
        val text = """Voici le resultat de l'extraction : {"numeroFacture":"F-001","montantTTC":1200} fin du message."""
        assertEquals("""{"numeroFacture":"F-001","montantTTC":1200}""", LlmJsonUtils.extractFirstJsonObject(text))
    }

    @Test
    fun `2 objets JSON encadres de texte - prend le PREMIER bloc balanced`() {
        // Cas pathologique. Greedy regex aurait capture du premier `{` jusqu'au
        // dernier `}` et donc inclus ` puis verification: ` au milieu, produisant
        // du non-JSON. Le scanner balanced doit s'arreter au premier `}` apparie.
        val text = """Voici le JSON facture: {"a":1,"b":2} puis verification: {"c":3}"""
        assertEquals("""{"a":1,"b":2}""", LlmJsonUtils.extractFirstJsonObject(text))
    }

    @Test
    fun `objet JSON avec accolades imbriquees - profondeur respectee`() {
        val text = """prefix {"outer": {"inner": {"deep": 42}, "x": "y"}, "k": [1,2,3]} suffix"""
        assertEquals(
            """{"outer": {"inner": {"deep": 42}, "x": "y"}, "k": [1,2,3]}""",
            LlmJsonUtils.extractFirstJsonObject(text)
        )
    }

    @Test
    fun `accolade dans une string ne compte pas`() {
        // Une string contient `{` et `}` litterales. Sans gestion des strings,
        // un compteur naif les compterait comme des accolades structurelles.
        val text = """{"label":"prix avec accolade}","value":10}"""
        assertEquals("""{"label":"prix avec accolade}","value":10}""", LlmJsonUtils.extractFirstJsonObject(text))
    }

    @Test
    fun `guillemet echappe dans une string ne ferme pas la string`() {
        val text = """{"raison":"Bouygues \"Telecom\" SARL","montant":1000}"""
        assertEquals(
            """{"raison":"Bouygues \"Telecom\" SARL","montant":1000}""",
            LlmJsonUtils.extractFirstJsonObject(text)
        )
    }

    @Test
    fun `aucune accolade - retourne null`() {
        assertNull(LlmJsonUtils.extractFirstJsonObject("juste du texte sans json"))
        assertNull(LlmJsonUtils.extractFirstJsonObject(""))
    }

    @Test
    fun `accolade ouvrante sans fermeture - retourne null`() {
        // JSON tronque : { ouvert mais pas referme. Le scanner ne doit pas
        // retourner un substring partiel (laisserait passer du faux JSON).
        assertNull(LlmJsonUtils.extractFirstJsonObject("""prefix {"a": 1, "b": 2"""))
    }

    @Test
    fun `texte avec backslash double dans string - parsing correct`() {
        // JSON valide contenant \\ dans une string : la sequence \\ est un
        // backslash echappe, suivi d'une fermeture de string normale.
        val text = """before {"path":"C:\\Users\\test","ok":true} after"""
        assertEquals("""{"path":"C:\\Users\\test","ok":true}""", LlmJsonUtils.extractFirstJsonObject(text))
    }
}
