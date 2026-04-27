package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.DocumentCorrectionRepository
import com.madaef.recondoc.repository.dossier.DossierRepository
import com.madaef.recondoc.repository.dossier.DocumentRepository
import com.madaef.recondoc.service.dossier.DocumentCorrectionService
import com.madaef.recondoc.service.validation.ValidationEngine
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import jakarta.persistence.EntityManager
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Garantit qu'une correction humaine sur un champ source (ex. montantTTC d'une
 * facture) est PRISE EN COMPTE par le moteur de validation lors d'un rerun :
 * le verdict doit refleter la valeur corrigee, pas la valeur extraite d'origine.
 *
 * Ce test garde la regression "rerun-figeait-le-verdict" : avant V38, la
 * correction etait stockee sur ResultatValidation.valeurTrouvee et n'avait
 * AUCUN effet sur les regles, qui relisaient `Facture.montantTtc` non corrige.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentCorrectionRerunTest {

    @Autowired lateinit var validationEngine: ValidationEngine
    @Autowired lateinit var correctionService: DocumentCorrectionService
    @Autowired lateinit var correctionRepo: DocumentCorrectionRepository
    @Autowired lateinit var dossierRepo: DossierRepository
    @Autowired lateinit var documentRepo: DocumentRepository
    @Autowired lateinit var entityManager: EntityManager

    private fun createDossier(type: DossierType = DossierType.BC) = dossierRepo.save(
        DossierPaiement(reference = "CORR-${System.nanoTime()}", type = type, statut = StatutDossier.BROUILLON)
    )

    private fun doc(dossier: DossierPaiement, type: TypeDocument, name: String = "${type.name.lowercase()}.pdf"): Document {
        val d = Document(dossier = dossier, typeDocument = type, nomFichier = name, cheminFichier = "/tmp/$name")
        dossier.documents.add(d)
        return documentRepo.saveAndFlush(d)
    }

    @Test
    fun `R01 - correction du montantTTC propagee au rerun fait basculer NON_CONFORME en CONFORME`() {
        val dossier = createDossier()
        val dFacture = doc(dossier, TypeDocument.FACTURE)
        val dBc = doc(dossier, TypeDocument.BON_COMMANDE)
        // documents deja ajoutes dans `doc(...)` via saveAndFlush
        dossier.factures.add(Facture(dossier = dossier, document = dFacture).apply {
            montantTtc = BigDecimal("1500.00")
            montantHt = BigDecimal("1250.00")
            montantTva = BigDecimal("250.00")
            tauxTva = BigDecimal("20")
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = dBc).apply {
            montantTtc = BigDecimal("1796.00")
            montantHt = BigDecimal("1496.66")
            montantTva = BigDecimal("299.34")
            tauxTva = BigDecimal("20")
        }
        dossierRepo.save(dossier)
        entityManager.flush()

        val initial = validationEngine.validate(dossier)
        val r01Initial = initial.first { it.regle == "R01" }
        assertEquals(StatutCheck.NON_CONFORME, r01Initial.statut,
            "Au depart, le TTC facture (1500) ne match pas celui du BC (1796) : R01 doit etre NON_CONFORME")

        // Correction : la vraie valeur du TTC facture est 1796.00 (l'OCR a lu 1500).
        correctionService.upsert(
            DocumentCorrectionService.CorrectionInput(
                documentId = dFacture.id!!,
                champ = "montantTTC",
                valeurCorrigee = "1796.00",
                regle = "R01",
                motif = "OCR a lu 1500 mais la facture mentionne 1796",
                corrigePar = "test@madaef.ma"
            )
        )

        val rerun = validationEngine.rerunRule(dossier, "R01", setOf("R01"))
        val r01After = rerun.first { it.regle == "R01" }
        assertEquals(StatutCheck.CONFORME, r01After.statut,
            "Apres correction du TTC facture a 1796, R01 doit etre CONFORME")
        // La valeur trouvee post-rerun reflete bien la correction.
        assertEquals("1796.00", r01After.valeurTrouvee)
    }

    @Test
    fun `R09 - correction d ICE propagee au rerun fait disparaitre l incoherence`() {
        val dossier = createDossier()
        val dFacture = doc(dossier, TypeDocument.FACTURE)
        val dBc = doc(dossier, TypeDocument.BON_COMMANDE)
        val dArf = doc(dossier, TypeDocument.ATTESTATION_FISCALE)
        dossier.documents.addAll(listOf(dFacture, dBc, dArf))
        dossier.factures.add(Facture(dossier = dossier, document = dFacture).apply {
            ice = "001509176000008" // bon ICE 15 chiffres
            montantTtc = BigDecimal("1796.00")
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = dBc).apply {
            montantTtc = BigDecimal("1796.00")
        }
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = dArf).apply {
            ice = "999000000000000" // ICE faux extrait par OCR
            estEnRegle = true
        }
        dossierRepo.save(dossier)
        entityManager.flush()

        val initial = validationEngine.validate(dossier)
        val r09Init = initial.first { it.regle == "R09" }
        assertEquals(StatutCheck.NON_CONFORME, r09Init.statut,
            "ICE attestation (999...) ne match pas l'ICE facture (001...): R09 doit etre NON_CONFORME")

        correctionService.upsert(
            DocumentCorrectionService.CorrectionInput(
                documentId = dArf.id!!,
                champ = "ice",
                valeurCorrigee = "001509176000008",
                regle = "R09"
            )
        )

        val rerun = validationEngine.rerunRule(dossier, "R09", setOf("R09"))
        val r09After = rerun.first { it.regle == "R09" }
        assertEquals(StatutCheck.CONFORME, r09After.statut,
            "Apres correction ICE attestation -> 001..., R09 doit etre CONFORME")
    }

    @Test
    fun `verdict-only override de statut sans correction de champ - le snapshot est preserve`() {
        // Cas inverse : si l'operateur force le statut CONFORME sans toucher
        // aux champs source, le rerun doit preserver son override (sinon on
        // perd le travail manuel a chaque rafraichissement).
        val dossier = createDossier()
        val dFacture = doc(dossier, TypeDocument.FACTURE)
        val dBc = doc(dossier, TypeDocument.BON_COMMANDE)
        // documents deja ajoutes dans `doc(...)` via saveAndFlush
        dossier.factures.add(Facture(dossier = dossier, document = dFacture).apply {
            montantTtc = BigDecimal("1500.00")
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = dBc).apply {
            montantTtc = BigDecimal("1796.00")
        }
        dossierRepo.save(dossier)
        entityManager.flush()

        val initial = validationEngine.validate(dossier)
        val r01 = initial.first { it.regle == "R01" }
        assertEquals(StatutCheck.NON_CONFORME, r01.statut)

        // Override verdict-only : on force CONFORME sans corriger les valeurs.
        r01.statutOriginal = r01.statut.name
        r01.statut = StatutCheck.CONFORME
        r01.commentaire = "Validation manuelle apres verification"
        r01.corrigePar = "operator@madaef.ma"

        val rerun = validationEngine.rerunRule(dossier, "R01")
        val r01After = rerun.first { it.regle == "R01" }
        assertEquals(StatutCheck.CONFORME, r01After.statut,
            "Sans correction de champ, l'override de statut doit etre preserve apres rerun")
        assertNotNull(r01After.statutOriginal)
        assertEquals("Validation manuelle apres verification", r01After.commentaire)
    }

    @Test
    fun `correction de champ purge l override statut prealable et applique le verdict frais`() {
        // Si l'operateur a d'abord force CONFORME (override statut) puis
        // corrige la valeur source, le rerun doit prendre le verdict frais
        // (pas remettre l'ancien override CONFORME). Sinon le statut reste fige
        // alors que les donnees ont change.
        val dossier = createDossier()
        val dFacture = doc(dossier, TypeDocument.FACTURE)
        val dBc = doc(dossier, TypeDocument.BON_COMMANDE)
        // documents deja ajoutes dans `doc(...)` via saveAndFlush
        dossier.factures.add(Facture(dossier = dossier, document = dFacture).apply {
            montantTtc = BigDecimal("1500.00")
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = dBc).apply {
            montantTtc = BigDecimal("1796.00")
        }
        dossierRepo.save(dossier)
        entityManager.flush()

        val initial = validationEngine.validate(dossier)
        val r01 = initial.first { it.regle == "R01" }
        // Override statut existant
        r01.statutOriginal = r01.statut.name
        r01.statut = StatutCheck.CONFORME

        // Correction de champ : on remet une valeur incorrecte volontairement
        // (1234 vs BC 1796) pour verifier que le verdict frais s'impose.
        correctionService.upsert(
            DocumentCorrectionService.CorrectionInput(
                documentId = dFacture.id!!, champ = "montantTTC", valeurCorrigee = "1234.00", regle = "R01"
            )
        )

        val rerun = validationEngine.rerunRule(dossier, "R01", setOf("R01"))
        val r01After = rerun.first { it.regle == "R01" }
        assertEquals(StatutCheck.NON_CONFORME, r01After.statut,
            "Apres correction de valeur, le verdict frais doit l'emporter sur l'ancien override CONFORME")
        assertEquals("1234.00", r01After.valeurTrouvee)
    }
}
