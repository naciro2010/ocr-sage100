package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.DossierRepository
import com.madaef.recondoc.service.validation.ValidationEngine
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dossiers de reference synthetiques : a chaque scenario, le verdict attendu
 * est fige dans ce test. Une regression dans ValidationEngine sera detectee
 * avant merge.
 *
 * Ce jeu doit s'enrichir progressivement via le sub-agent `controls-auditor`.
 * Objectif : >= 15 scenarios a terme, couvrant tous les axes metier.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GoldenDossiersRegressionTest {

    @Autowired lateinit var validationEngine: ValidationEngine
    @Autowired lateinit var dossierRepo: DossierRepository

    private fun newDossier(type: DossierType = DossierType.BC) = dossierRepo.save(
        DossierPaiement(reference = "GOLDEN-${System.nanoTime()}", type = type, statut = StatutDossier.BROUILLON)
    )

    private fun doc(dossier: DossierPaiement, type: TypeDocument, name: String = "golden.pdf") =
        Document(dossier = dossier, typeDocument = type, nomFichier = name, cheminFichier = "/tmp/$name")

    @Test
    fun `golden 01 dossier BC parfait - tous les R deterministes conformes`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val bcDoc = doc(dossier, TypeDocument.BON_COMMANDE, "bc.pdf")
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        val ckDoc = doc(dossier, TypeDocument.CHECKLIST_AUTOCONTROLE, "ck.pdf")
        val tcDoc = doc(dossier, TypeDocument.TABLEAU_CONTROLE, "tc.pdf")
        dossier.documents.addAll(listOf(fDoc, bcDoc, opDoc, ckDoc, tcDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-2026-001"; dateFacture = LocalDate.of(2026, 3, 10)
            montantHt = BigDecimal("1000.00"); montantTva = BigDecimal("200.00"); montantTtc = BigDecimal("1200.00")
            tauxTva = BigDecimal("20"); fournisseur = "ACME SARL"; ice = "001509176000008"
            identifiantFiscal = "12345"; rib = "0000000000000000"
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = bcDoc).apply {
            reference = "BC-2026-001"; dateBc = LocalDate.of(2026, 2, 1)
            montantHt = BigDecimal("1000.00"); montantTva = BigDecimal("200.00"); montantTtc = BigDecimal("1200.00")
            tauxTva = BigDecimal("20"); fournisseur = "ACME SARL"
        }
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-2026-001"; dateEmission = LocalDate.of(2026, 3, 20)
            montantOperation = BigDecimal("1200.00")
            referenceFacture = "F-2026-001"; referenceBcOuContrat = "BC-2026-001"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        assertTrue(results.all { it.statut != StatutCheck.NON_CONFORME },
            "Dossier parfait : aucune regle ne doit etre NON_CONFORME. Incoherences : " +
                results.filter { it.statut == StatutCheck.NON_CONFORME }
                    .joinToString("; ") { "${it.regle}: ${it.detail}" })
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R01" }.statut)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R02" }.statut)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R04" }.statut)
    }

    @Test
    fun `golden 02 arithmetique HT TVA TTC incoherente - R16 NON_CONFORME`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        dossier.documents.add(fDoc)

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-BAD"; dateFacture = LocalDate.of(2026, 3, 1)
            montantHt = BigDecimal("1000.00"); montantTva = BigDecimal("200.00"); montantTtc = BigDecimal("1500.00")
            tauxTva = BigDecimal("20"); fournisseur = "X"
        })
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r16 = results.firstOrNull { it.regle == "R16" }
        assertTrue(r16 != null, "R16 doit s'executer quand HT+TVA+TTC sont connus")
        assertEquals(StatutCheck.NON_CONFORME, r16.statut,
            "R16 doit etre NON_CONFORME pour HT+TVA != TTC")
    }

    @Test
    fun `golden 03 attestation fiscale expiree - R18 NON_CONFORME`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.of(2026, 3, 15); fournisseur = "X"; ice = "001"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.of(2025, 8, 1)
            raisonSociale = "X"; ice = "001"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r18 = results.firstOrNull { it.regle == "R18" }
        assertTrue(r18 != null, "R18 doit s'executer quand l'attestation a une date d'edition")
        assertEquals(StatutCheck.NON_CONFORME, r18.statut,
            "R18 doit etre NON_CONFORME pour une attestation emise il y a plus de 6 mois")
    }
}
