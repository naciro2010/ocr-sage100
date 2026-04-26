package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.extraction.ExtractionPrompts
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifie le contrat de split du system prompt en 2 blocs cacheables :
 * `STABLE_COMMON_PREFIX` (regles communes Maroc, partage cross-type) +
 * `specificFor(type)` (few-shots + schema specifiques au type).
 *
 * Le split est exploite par DossierService pour envoyer 2 content blocks
 * `cache_control` distincts a Anthropic, ce qui permet au prefixe stable
 * de hit le cache cross-type (-60-80% cout prompt sur upload mixte).
 */
class ExtractionPromptsTest {

    @Test
    fun `STABLE_COMMON_PREFIX expose les regles communes Maroc`() {
        val prefix = ExtractionPrompts.STABLE_COMMON_PREFIX
        assertTrue(prefix.contains("ICE"), "STABLE_COMMON_PREFIX doit couvrir ICE")
        assertTrue(prefix.contains("RIB"), "STABLE_COMMON_PREFIX doit couvrir RIB")
        assertTrue(prefix.contains("TVA"), "STABLE_COMMON_PREFIX doit couvrir TVA")
        assertTrue(prefix.contains("anti-hallucination") || prefix.contains("FIABILITE"),
            "STABLE_COMMON_PREFIX doit contenir la regle anti-hallucination")
    }

    @Test
    fun `specificFor retourne le prompt sans le bloc commun pour les types principaux`() {
        for (type in listOf(TypeDocument.FACTURE, TypeDocument.BON_COMMANDE,
                            TypeDocument.ORDRE_PAIEMENT, TypeDocument.ATTESTATION_FISCALE)) {
            val specific = ExtractionPrompts.specificFor(type)
            assertNotNull(specific, "specificFor($type) doit etre defini")
            assertTrue(specific.isNotBlank(), "specificFor($type) ne doit pas etre vide")
            assertTrue(!specific.contains(ExtractionPrompts.STABLE_COMMON_PREFIX),
                "specificFor($type) ne doit PAS dupliquer STABLE_COMMON_PREFIX (sinon cache invalide)")
        }
    }

    @Test
    fun `specificFor + STABLE_COMMON_PREFIX couvrent ensemble le prompt complet pour FACTURE`() {
        val specific = ExtractionPrompts.specificFor(TypeDocument.FACTURE)!!
        // Markers caracteristiques du bloc specifique FACTURE (intro + schema)
        assertTrue(specific.contains("extracteur de donnees de factures"),
            "Bloc specifique FACTURE doit contenir l'intro extracteur facture")
        assertTrue(specific.contains("\"numeroFacture\""),
            "Bloc specifique FACTURE doit contenir le schema numeroFacture")
        // Le prompt complet doit contenir les deux blocs
        assertTrue(ExtractionPrompts.FACTURE.contains(specific),
            "Le prompt FACTURE complet doit contenir le bloc specifique")
        assertTrue(ExtractionPrompts.FACTURE.contains(ExtractionPrompts.STABLE_COMMON_PREFIX),
            "Le prompt FACTURE complet doit contenir le bloc commun")
        // Specific et COMMON doivent etre disjoints (pas de duplication = cache OK)
        assertTrue(!specific.contains(ExtractionPrompts.STABLE_COMMON_PREFIX),
            "Le specific NE doit PAS contenir COMMON (duplication = cache invalide)")
    }

    @Test
    fun `few-shots negatifs presents pour ATTESTATION_FISCALE PV CONTRAT`() {
        // ATTESTATION : 3 shots (en regle, NON en regle, ambigu)
        val attestation = ExtractionPrompts.ATTESTATION_FISCALE
        assertTrue(attestation.contains("estEnRegle\":false"),
            "ATTESTATION doit contenir un shot avec estEnRegle=false")
        assertTrue(attestation.contains("estEnRegle\":null"),
            "ATTESTATION doit contenir un shot avec estEnRegle=null (ambigu)")

        // PV : 2 shots (standard + signataire fournisseur manquant)
        val pv = ExtractionPrompts.PV_RECEPTION
        assertTrue(pv.contains("EXEMPLE 1") || pv.contains("EXEMPLE ("),
            "PV_RECEPTION doit contenir au moins un few-shot")
        assertTrue(pv.contains("signataireFournisseur\":null"),
            "PV_RECEPTION doit contenir un shot signature fournisseur manquante")

        // CONTRAT : 1 shot avec grille tarifaire MADAEF
        val contrat = ExtractionPrompts.CONTRAT_AVENANT
        assertTrue(contrat.contains("grillesTarifaires"),
            "CONTRAT_AVENANT doit contenir un few-shot avec grille tarifaire")
    }
}
