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

    @Test
    fun `golden 04 anti-doublon R21 CONFORME quand aucun doublon`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        dossier.documents.add(fDoc)
        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-UNIQUE-${System.nanoTime()}"
            dateFacture = LocalDate.of(2026, 3, 15)
            fournisseur = "Fournisseur Unique SARL"
            montantTtc = BigDecimal("12345.67")
        })
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r21 = results.firstOrNull { it.regle == "R21" }
        assertTrue(r21 != null, "R21 doit s'executer quand il y a une facture")
        assertEquals(StatutCheck.CONFORME, r21.statut,
            "R21 doit etre CONFORME quand aucun doublon detecte")
    }

    @Test
    fun `golden 05 anti-doublon R21 NON_CONFORME si meme numero sur 2 dossiers`() {
        val shared = "F-DUP-${System.nanoTime()}"

        val dossier1 = newDossier()
        val fDoc1 = doc(dossier1, TypeDocument.FACTURE, "f1.pdf")
        dossier1.documents.add(fDoc1)
        dossier1.factures.add(Facture(dossier = dossier1, document = fDoc1).apply {
            numeroFacture = shared
            dateFacture = LocalDate.of(2026, 2, 1)
            fournisseur = "ACME SARL"
            montantTtc = BigDecimal("1000.00")
        })
        dossierRepo.saveAndFlush(dossier1)

        val dossier2 = newDossier()
        val fDoc2 = doc(dossier2, TypeDocument.FACTURE, "f2.pdf")
        dossier2.documents.add(fDoc2)
        dossier2.factures.add(Facture(dossier = dossier2, document = fDoc2).apply {
            numeroFacture = shared
            dateFacture = LocalDate.of(2026, 3, 1)
            fournisseur = "ACME SARL"
            montantTtc = BigDecimal("1000.00")
        })
        dossierRepo.saveAndFlush(dossier2)

        val results = validationEngine.validate(dossier2)
        val r21 = results.firstOrNull { it.regle == "R21" }
        assertTrue(r21 != null)
        assertEquals(StatutCheck.NON_CONFORME, r21.statut,
            "R21 doit detecter le meme numero facture sur un autre dossier")
        assertTrue(r21.detail!!.contains(shared) || r21.detail!!.contains("numero"),
            "Le detail doit mentionner le conflit: ${r21.detail}")
    }

    @Test
    fun `golden 06 R22 NON_CONFORME si OP emis avant reception`() {
        val dossier = newDossier(DossierType.CONTRACTUEL)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        val pvDoc = doc(dossier, TypeDocument.PV_RECEPTION, "pv.pdf")
        dossier.documents.addAll(listOf(opDoc, pvDoc))

        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-001"
            dateEmission = LocalDate.of(2026, 2, 1)
            montantOperation = BigDecimal("1000.00")
        }
        dossier.pvReception = PvReception(dossier = dossier, document = pvDoc).apply {
            dateReception = LocalDate.of(2026, 3, 1)
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r22 = results.firstOrNull { it.regle == "R22" }
        assertTrue(r22 != null)
        assertEquals(StatutCheck.NON_CONFORME, r22.statut,
            "R22 doit etre NON_CONFORME: OP du 01/02 precede PV reception du 01/03")
    }

    @Test
    fun `golden 07 R22 CONFORME si OP emis apres reception`() {
        val dossier = newDossier(DossierType.CONTRACTUEL)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        val pvDoc = doc(dossier, TypeDocument.PV_RECEPTION, "pv.pdf")
        dossier.documents.addAll(listOf(opDoc, pvDoc))

        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-002"
            dateEmission = LocalDate.of(2026, 3, 15)
            montantOperation = BigDecimal("1000.00")
        }
        dossier.pvReception = PvReception(dossier = dossier, document = pvDoc).apply {
            dateReception = LocalDate.of(2026, 3, 1)
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r22 = results.firstOrNull { it.regle == "R22" }
        assertTrue(r22 != null)
        assertEquals(StatutCheck.CONFORME, r22.statut,
            "R22 doit etre CONFORME: OP du 15/03 posterieur a PV reception du 01/03")
    }

    @Test
    fun `golden 08 R09 NON_CONFORME si ICE different entre facture et attestation`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.of(2026, 3, 15); fournisseur = "ACME"
            ice = "001509176000008"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.now().minusDays(10)
            raisonSociale = "ACME"; ice = "999999999999999"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r09 = results.firstOrNull { it.regle == "R09" }
        assertTrue(r09 != null)
        assertEquals(StatutCheck.NON_CONFORME, r09.statut,
            "R09 doit etre NON_CONFORME quand ICE facture != ICE attestation")
    }

    @Test
    fun `golden 09 R10 NON_CONFORME si IF different entre facture et attestation`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.of(2026, 3, 15); fournisseur = "ACME"
            ice = "001509176000008"; identifiantFiscal = "123456"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.now().minusDays(10)
            raisonSociale = "ACME"; ice = "001509176000008"; identifiantFiscal = "999999"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r10 = results.firstOrNull { it.regle == "R10" }
        assertTrue(r10 != null)
        assertEquals(StatutCheck.NON_CONFORME, r10.statut,
            "R10 doit etre NON_CONFORME quand IF facture != IF attestation")
    }

    @Test
    fun `golden 10 R04 NON_CONFORME si OP superieur au TTC facture sans retenues`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(fDoc, opDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 3, 1)
            montantTtc = BigDecimal("1000.00"); fournisseur = "X"
        })
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 3, 15)
            montantOperation = BigDecimal("2500.00")
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r04 = results.firstOrNull { it.regle == "R04" }
        assertTrue(r04 != null)
        assertEquals(StatutCheck.NON_CONFORME, r04.statut,
            "R04 doit etre NON_CONFORME quand OP > TTC facture (sans retenues)")
    }

    @Test
    fun `golden 11 R06 NON_CONFORME si retenue base x taux ne correspond pas au montant`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(fDoc, opDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 3, 1)
            montantTtc = BigDecimal("120000.00"); fournisseur = "X"
        })
        val op = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 3, 15)
            montantOperation = BigDecimal("95000.00")
        }
        op.retenues.add(Retenue(ordrePaiement = op, type = TypeRetenue.TVA_SOURCE).apply {
            base = BigDecimal("20000.00"); taux = BigDecimal("75")
            montant = BigDecimal("5000.00")
        })
        dossier.ordrePaiement = op
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r06 = results.firstOrNull { it.regle == "R06" }
        assertTrue(r06 != null, "R06 doit s'executer quand il y a une retenue")
        assertEquals(StatutCheck.NON_CONFORME, r06.statut,
            "R06 doit etre NON_CONFORME quand base x taux (15000) != montant declare (5000)")
    }

    @Test
    fun `golden 12 R14 CONFORME quand les noms fournisseurs sont des variantes du meme (matching semantique)`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val bcDoc = doc(dossier, TypeDocument.BON_COMMANDE, "bc.pdf")
        dossier.documents.addAll(listOf(fDoc, bcDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 3, 1)
            montantHt = BigDecimal("1000.00"); montantTva = BigDecimal("200.00")
            montantTtc = BigDecimal("1200.00"); tauxTva = BigDecimal("20")
            fournisseur = "Maymana Patisserie"; ice = "001509176000008"
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = bcDoc).apply {
            reference = "BC-1"; dateBc = LocalDate.of(2026, 2, 1)
            montantHt = BigDecimal("1000.00"); montantTva = BigDecimal("200.00")
            montantTtc = BigDecimal("1200.00"); tauxTva = BigDecimal("20")
            fournisseur = "Maymana Patisse"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r14 = results.firstOrNull { it.regle == "R14" }
        assertTrue(r14 != null)
        assertTrue(r14.statut == StatutCheck.CONFORME || r14.statut == StatutCheck.AVERTISSEMENT,
            "R14 ne doit pas etre NON_CONFORME pour des variantes orthographiques du meme fournisseur (got ${r14.statut})")
    }

    @Test
    fun `golden 13 R16 NON_CONFORME si HT zero avec montant TTC positif`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        dossier.documents.add(fDoc)

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-ZERO"; dateFacture = LocalDate.of(2026, 3, 1)
            montantHt = BigDecimal("0.00"); montantTva = BigDecimal("100.00")
            montantTtc = BigDecimal("1000.00"); tauxTva = BigDecimal("20")
            fournisseur = "X"
        })
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r16 = results.firstOrNull { it.regle == "R16" }
        assertTrue(r16 != null)
        assertEquals(StatutCheck.NON_CONFORME, r16.statut,
            "R16 doit etre NON_CONFORME pour HT=0 + TVA=100 != TTC=1000")
    }

    @Test
    fun `golden 14 R14 AVERTISSEMENT si fournisseurs vraiment differents entre facture et BC`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val bcDoc = doc(dossier, TypeDocument.BON_COMMANDE, "bc.pdf")
        dossier.documents.addAll(listOf(fDoc, bcDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 3, 1)
            montantTtc = BigDecimal("1000.00"); fournisseur = "Maymana Patisserie"
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = bcDoc).apply {
            reference = "BC-1"; dateBc = LocalDate.of(2026, 2, 1)
            montantTtc = BigDecimal("1000.00"); fournisseur = "SOCIETE NOUVELLE PAPETERIE CARDONE SARL"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r14 = results.firstOrNull { it.regle == "R14" }
        assertTrue(r14 != null)
        assertEquals(StatutCheck.AVERTISSEMENT, r14.statut,
            "R14 doit etre AVERTISSEMENT quand fournisseurs reellement differents")
    }

    @Test
    fun `golden 16 R18b NON_CONFORME si OP emis apres expiration de l'attestation`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, opDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 2, 10)
            montantTtc = BigDecimal("1000.00"); fournisseur = "X"; ice = "001"
        })
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 4, 1)
            montantOperation = BigDecimal("1000.00")
        }
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.of(2025, 9, 1)
            raisonSociale = "X"; ice = "001"; estEnRegle = true
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r18b = results.firstOrNull { it.regle == "R18b" }
        assertTrue(r18b != null, "R18b doit s'executer quand attestation et OP sont presents")
        assertEquals(StatutCheck.NON_CONFORME, r18b.statut,
            "R18b doit etre NON_CONFORME : OP du 01/04/2026 posterieur a l'expiration du 01/03/2026")
    }

    @Test
    fun `golden 17 R18b CONFORME si OP emis avant expiration de l'attestation`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, opDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-2"; dateFacture = LocalDate.of(2026, 3, 1)
            montantTtc = BigDecimal("1000.00"); fournisseur = "X"; ice = "001"
        })
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-2"; dateEmission = LocalDate.of(2026, 3, 20)
            montantOperation = BigDecimal("1000.00")
        }
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.of(2026, 1, 15)
            raisonSociale = "X"; ice = "001"; estEnRegle = true
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r18b = results.firstOrNull { it.regle == "R18b" }
        assertTrue(r18b != null)
        assertEquals(StatutCheck.CONFORME, r18b.statut,
            "R18b doit etre CONFORME : OP du 20/03/2026 anterieur a l'expiration du 15/07/2026")
    }

    @Test
    fun `golden 18 R23 NON_CONFORME si attestation indique que la societe n'est pas en regle`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-3"; dateFacture = LocalDate.of(2026, 3, 1)
            montantTtc = BigDecimal("1000.00"); fournisseur = "X"; ice = "001"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.now().minusDays(10)
            raisonSociale = "X"; ice = "001"; estEnRegle = false
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r23 = results.firstOrNull { it.regle == "R23" }
        assertTrue(r23 != null, "R23 doit s'executer quand l'attestation est presente")
        assertEquals(StatutCheck.NON_CONFORME, r23.statut,
            "R23 doit etre NON_CONFORME quand la case 'pas en regle' est cochee (estEnRegle=false)")
    }

    @Test
    fun `golden 15 R11 NON_CONFORME si RIB facture et OP differents`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(fDoc, opDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 3, 1)
            montantTtc = BigDecimal("1000.00"); fournisseur = "X"
            rib = "011810000011111111111193"
        })
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 3, 15)
            montantOperation = BigDecimal("1000.00")
            rib = "011820000022222222222273"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r11 = results.firstOrNull { it.regle == "R11" }
        assertTrue(r11 != null)
        assertEquals(StatutCheck.NON_CONFORME, r11.statut,
            "R11 doit etre NON_CONFORME quand RIB facture != RIB OP")
    }
}
