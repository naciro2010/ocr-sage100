package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.DocumentRepository
import com.madaef.recondoc.repository.dossier.DossierRepository
import com.madaef.recondoc.repository.dossier.FactureRepository
import com.madaef.recondoc.service.DossierService
import com.madaef.recondoc.service.validation.ValidationEngine
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Couvre la persistance des corrections humaines saisies dans l'UI :
 *  - la valeur reste en base ;
 *  - elle est reportee sur l'entite typee (Facture) ;
 *  - une relance de regles utilise la valeur corrigee ;
 *  - les champs systeme `_*` (calcules) ne sont pas exposes a l'UI ;
 *  - les champs systeme ne sont pas editables.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExtractionCorrectionTest {

    @Autowired lateinit var dossierService: DossierService
    @Autowired lateinit var dossierRepo: DossierRepository
    @Autowired lateinit var documentRepo: DocumentRepository
    @Autowired lateinit var factureRepo: FactureRepository
    @Autowired lateinit var validationEngine: ValidationEngine

    private fun setupFactureWithExtraction(initialTtc: String): Pair<DossierPaiement, Document> {
        val dossier = dossierRepo.save(DossierPaiement(
            reference = "TEST-${System.nanoTime()}",
            type = DossierType.BC,
            statut = StatutDossier.BROUILLON
        ))
        val doc = Document(
            dossier = dossier,
            typeDocument = TypeDocument.FACTURE,
            nomFichier = "f.pdf",
            cheminFichier = "/tmp/f.pdf",
            statutExtraction = StatutExtraction.EXTRAIT,
            donneesExtraites = mapOf(
                "numeroFacture" to "F-001",
                "fournisseur" to "TEST SARL",
                "montantHT" to BigDecimal("1000.00"),
                "montantTVA" to BigDecimal("200.00"),
                "tauxTVA" to BigDecimal("20"),
                "montantTTC" to BigDecimal(initialTtc),
                // Champs systeme/calcules : ne doivent pas etre exposes a l'UI
                "_confidence" to 0.95,
                "_warnings" to listOf("warn-1"),
                "_qr" to mapOf("payload" to "x", "officialHost" to true)
            )
        )
        dossier.documents.add(doc)
        documentRepo.save(doc)
        // Cree l'entite typee pour que ValidationEngine y lise.
        factureRepo.save(Facture(dossier = dossier, document = doc).apply {
            numeroFacture = "F-001"; fournisseur = "TEST SARL"
            montantHt = BigDecimal("1000.00"); montantTva = BigDecimal("200.00")
            tauxTva = BigDecimal("20"); montantTtc = BigDecimal(initialTtc)
        })
        return dossier to doc
    }

    @Test
    fun `correction d'un champ extrait persiste en base`() {
        val (dossier, doc) = setupFactureWithExtraction(initialTtc = "1196.00")
        dossierService.updateDocumentExtractedField(
            dossier.id!!, doc.id!!, "montantTTC", "1200.00"
        )
        val reloaded = documentRepo.findById(doc.id!!).get()
        assertEquals(BigDecimal("1200.00"), reloaded.donneesExtraites!!["montantTTC"])
    }

    @Test
    fun `correction d'un champ extrait met a jour l'entite typee Facture`() {
        val (dossier, doc) = setupFactureWithExtraction(initialTtc = "1196.00")
        dossierService.updateDocumentExtractedField(
            dossier.id!!, doc.id!!, "montantTTC", "1200.00"
        )
        val facture = factureRepo.findByDocumentId(doc.id!!)
        assertNotNull(facture)
        assertEquals(0, facture!!.montantTtc!!.compareTo(BigDecimal("1200.00")),
            "Facture.montantTtc doit refleter la correction (vu par les regles a la prochaine relance)")
    }

    @Test
    fun `correction est utilisee par ValidationEngine au lieu de la valeur d'origine`() {
        val (dossier, doc) = setupFactureWithExtraction(initialTtc = "9999.00")
        // Ajoute un BC avec le montant cible : avant correction, R01 sera NON_CONFORME.
        val bcDoc = Document(
            dossier = dossier, typeDocument = TypeDocument.BON_COMMANDE,
            nomFichier = "bc.pdf", cheminFichier = "/tmp/bc.pdf",
            statutExtraction = StatutExtraction.EXTRAIT
        )
        dossier.documents.add(bcDoc)
        documentRepo.save(bcDoc)
        dossier.bonCommande = BonCommande(dossier = dossier, document = bcDoc).apply {
            montantTtc = BigDecimal("1200.00"); montantHt = BigDecimal("1000.00")
            montantTva = BigDecimal("200.00"); tauxTva = BigDecimal("20")
        }
        dossierRepo.save(dossier)

        val before = validationEngine.validate(dossier)
        assertEquals(StatutCheck.NON_CONFORME, before.first { it.regle == "R01" }.statut,
            "Avant correction, R01 doit etre NON_CONFORME (9999 vs 1200)")

        dossierService.updateDocumentExtractedField(
            dossier.id!!, doc.id!!, "montantTTC", "1200.00"
        )

        // Recharge le dossier (les valeurs typees ont ete mises a jour).
        val reloaded = dossierRepo.findByIdWithAll(dossier.id!!).orElseThrow()
        val after = validationEngine.validate(reloaded)
        assertEquals(StatutCheck.CONFORME, after.first { it.regle == "R01" }.statut,
            "Apres correction, R01 doit etre CONFORME : la correction humaine est prise en compte")
    }

    @Test
    fun `getDocumentExtractedData filtre les champs systeme prefixe underscore`() {
        val (dossier, doc) = setupFactureWithExtraction(initialTtc = "1200.00")
        val visible = dossierService.getDocumentExtractedData(dossier.id!!, doc.id!!)
        assertNotNull(visible)
        assertTrue(visible!!.containsKey("montantTTC"))
        assertTrue(visible.containsKey("numeroFacture"))
        assertFalse(visible.containsKey("_confidence"), "_confidence ne doit pas etre expose a l'UI")
        assertFalse(visible.containsKey("_warnings"), "_warnings ne doit pas etre expose a l'UI")
        assertFalse(visible.containsKey("_qr"), "_qr (champ calcule) ne doit pas etre expose a l'UI")

        // Mais en base, ces champs sont conserves pour l'audit / le moteur de regles.
        val raw = documentRepo.findById(doc.id!!).get().donneesExtraites
        assertNotNull(raw)
        assertTrue(raw!!.containsKey("_qr"), "_qr doit rester en base")
    }

    @Test
    fun `correction d'un champ systeme est rejetee`() {
        val (dossier, doc) = setupFactureWithExtraction(initialTtc = "1200.00")
        var caught: IllegalArgumentException? = null
        try {
            dossierService.updateDocumentExtractedField(
                dossier.id!!, doc.id!!, "_confidence", "1.0"
            )
        } catch (e: IllegalArgumentException) {
            caught = e
        }
        assertNotNull(caught, "Editer un champ systeme prefixe `_` doit lever IllegalArgumentException")
    }

    @Test
    fun `correction par chaine vide efface la valeur`() {
        val (dossier, doc) = setupFactureWithExtraction(initialTtc = "1200.00")
        dossierService.updateDocumentExtractedField(
            dossier.id!!, doc.id!!, "numeroFacture", ""
        )
        val reloaded = documentRepo.findById(doc.id!!).get()
        assertNull(reloaded.donneesExtraites!!["numeroFacture"],
            "Une chaine vide doit etre interpretee comme un effacement (null)")
    }
}
