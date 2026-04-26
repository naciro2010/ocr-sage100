package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.entity.engagement.EngagementMarche
import com.madaef.recondoc.repository.dossier.DossierRepository
import com.madaef.recondoc.repository.engagement.EngagementMarcheRepository
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
    @Autowired lateinit var marcheRepo: EngagementMarcheRepository

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
    fun `golden 16 R18 CONFORME le jour exact d'expiration (borne inclusive)`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.now(); fournisseur = "X"; ice = "001"
            identifiantFiscal = "IF-001"
        })
        // edition aujourd'hui - 6 mois pile : la borne est inclusive, donc CONFORME
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.now().minusMonths(6)
            raisonSociale = "X"; ice = "001"; identifiantFiscal = "IF-001"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r18 = results.firstOrNull { it.regle == "R18" }
        assertTrue(r18 != null, "R18 doit s'executer")
        assertEquals(StatutCheck.CONFORME, r18.statut,
            "R18 doit etre CONFORME le jour exact d'expiration (borne 6 mois inclusive)")
    }

    @Test
    fun `golden 17 R17b NON_CONFORME si OP emis avant la facture (paiement antidate)`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(fDoc, opDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 3, 15)
            montantTtc = BigDecimal("1000.00"); fournisseur = "X"
            ice = "001"; identifiantFiscal = "IF-001"
        })
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 3, 1) // antidate
            montantOperation = BigDecimal("1000.00")
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r17b = results.firstOrNull { it.regle == "R17b" }
        assertTrue(r17b != null, "R17b doit s'executer")
        assertEquals(StatutCheck.NON_CONFORME, r17b.statut,
            "R17b doit etre NON_CONFORME quand OP precede la facture (paiement antidate)")
    }

    @Test
    fun `golden 18 R17a NON_CONFORME si facture anterieure au BC`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val bcDoc = doc(dossier, TypeDocument.BON_COMMANDE, "bc.pdf")
        dossier.documents.addAll(listOf(fDoc, bcDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 1, 15) // avant BC
            montantTtc = BigDecimal("1000.00"); fournisseur = "X"
            ice = "001"; identifiantFiscal = "IF-001"
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = bcDoc).apply {
            reference = "BC-1"; dateBc = LocalDate.of(2026, 3, 1)
            montantTtc = BigDecimal("1000.00"); fournisseur = "X"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r17a = results.firstOrNull { it.regle == "R17a" }
        assertTrue(r17a != null)
        assertEquals(StatutCheck.NON_CONFORME, r17a.statut,
            "R17a doit etre NON_CONFORME quand facture anterieure au BC")
    }

    @Test
    fun `golden 19 R09 NON_CONFORME si facture sans ICE alors qu'attestation en a un`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.now(); fournisseur = "X"
            // Pas d'ICE sur la facture
            identifiantFiscal = "IF-001"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.now().minusDays(10)
            raisonSociale = "X"; ice = "001509176000008"; identifiantFiscal = "IF-001"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r09 = results.firstOrNull { it.regle == "R09" }
        assertTrue(r09 != null)
        assertEquals(StatutCheck.NON_CONFORME, r09.statut,
            "R09 doit etre NON_CONFORME : ICE manquant sur facture B2B Maroc")
    }

    @Test
    fun `golden 20 R21 CONFORME pour compensation facture + avoir meme numero`() {
        val sharedNumero = "F-COMPENSATE-${System.nanoTime()}"

        // Dossier 1 : facture normale
        val dossier1 = newDossier()
        val fDoc1 = doc(dossier1, TypeDocument.FACTURE, "f1.pdf")
        dossier1.documents.add(fDoc1)
        dossier1.factures.add(Facture(dossier = dossier1, document = fDoc1).apply {
            numeroFacture = sharedNumero
            dateFacture = LocalDate.of(2026, 2, 1)
            fournisseur = "ACME"
            montantTtc = BigDecimal("1000.00")
        })
        dossierRepo.saveAndFlush(dossier1)

        // Dossier 2 : avoir (montant negatif, meme numero) → compensation, pas un doublon
        val dossier2 = newDossier()
        val fDoc2 = doc(dossier2, TypeDocument.FACTURE, "av.pdf")
        dossier2.documents.add(fDoc2)
        dossier2.factures.add(Facture(dossier = dossier2, document = fDoc2).apply {
            numeroFacture = sharedNumero
            dateFacture = LocalDate.of(2026, 2, 15)
            fournisseur = "ACME"
            montantTtc = BigDecimal("-1000.00") // avoir
        })
        dossierRepo.saveAndFlush(dossier2)

        val results = validationEngine.validate(dossier2)
        val r21 = results.firstOrNull { it.regle == "R21" }
        assertTrue(r21 != null)
        assertEquals(StatutCheck.CONFORME, r21.statut,
            "R21 doit etre CONFORME quand le 'doublon' est en realite un avoir (compensation legitime)")
        assertTrue(r21.detail!!.lowercase().contains("compensation") || r21.detail!!.lowercase().contains("avoir"),
            "Le detail doit mentionner la compensation : ${r21.detail}")
    }

    @Test
    fun `golden 21 R21 NON_CONFORME pour vrai doublon (deux factures positives meme numero)`() {
        val shared = "F-DUP2-${System.nanoTime()}"

        val dossier1 = newDossier()
        val fDoc1 = doc(dossier1, TypeDocument.FACTURE, "f1.pdf")
        dossier1.documents.add(fDoc1)
        dossier1.factures.add(Facture(dossier = dossier1, document = fDoc1).apply {
            numeroFacture = shared
            dateFacture = LocalDate.of(2026, 2, 1)
            fournisseur = "ACME"
            montantTtc = BigDecimal("1000.00")
        })
        dossierRepo.saveAndFlush(dossier1)

        val dossier2 = newDossier()
        val fDoc2 = doc(dossier2, TypeDocument.FACTURE, "f2.pdf")
        dossier2.documents.add(fDoc2)
        dossier2.factures.add(Facture(dossier = dossier2, document = fDoc2).apply {
            numeroFacture = shared
            dateFacture = LocalDate.of(2026, 3, 1)
            fournisseur = "ACME"
            montantTtc = BigDecimal("1000.00")
        })
        dossierRepo.saveAndFlush(dossier2)

        val results = validationEngine.validate(dossier2)
        val r21 = results.firstOrNull { it.regle == "R21" }
        assertTrue(r21 != null)
        assertEquals(StatutCheck.NON_CONFORME, r21.statut,
            "R21 doit etre NON_CONFORME pour deux factures positives meme numero (vrai doublon)")
    }

    @Test
    fun `golden 22 R09b NON_CONFORME si ICE facture mal forme (moins de 15 chiffres)`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.of(2026, 3, 15); fournisseur = "X"
            ice = "15091760000" // 11 chiffres : padding OCR perdu
            identifiantFiscal = "IF-1"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.now().minusDays(10)
            raisonSociale = "X"; ice = "001509176000008"; identifiantFiscal = "IF-1"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r09b = results.firstOrNull { it.regle == "R09b" }
        assertTrue(r09b != null, "R09b doit s'executer quand au moins un ICE est extrait")
        assertEquals(StatutCheck.NON_CONFORME, r09b.statut,
            "R09b doit etre NON_CONFORME pour un ICE a 11 chiffres")
    }

    @Test
    fun `golden 23 R09b CONFORME pour ICE 15 chiffres exacts`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.of(2026, 3, 15); fournisseur = "X"
            ice = "001509176000008" // 15 chiffres avec zeros initiaux significatifs
            identifiantFiscal = "IF-1"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.now().minusDays(10)
            raisonSociale = "X"; ice = "001509176000008"; identifiantFiscal = "IF-1"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r09b = results.firstOrNull { it.regle == "R09b" }
        assertTrue(r09b != null)
        assertEquals(StatutCheck.CONFORME, r09b.statut, "ICE 15 chiffres exacts doit etre CONFORME format")
        // R09 (coherence) doit aussi etre CONFORME : meme ICE.
        val r09 = results.firstOrNull { it.regle == "R09" }
        assertEquals(StatutCheck.CONFORME, r09?.statut, "R09 coherence doit etre CONFORME")
    }

    @Test
    fun `golden 24 R09 NON_CONFORME quand 2 ICE divergent uniquement par les zeros initiaux (regression bug normalizeId)`() {
        // Avant fix : `normalizeId` retirait les zeros de tete -> les deux ICE
        // matchaient apres normalisation et R09 retournait CONFORME (faux negatif).
        // Apres fix : `normalizeIce` preserve les zeros, R09 detecte la difference.
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.of(2026, 3, 15); fournisseur = "X"
            ice = "001509176000008" // 15 chiffres
            identifiantFiscal = "IF-1"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.now().minusDays(10)
            raisonSociale = "X"
            ice = "1509176000008" // 13 chiffres (autre entreprise OU OCR degrade)
            identifiantFiscal = "IF-1"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r09 = results.firstOrNull { it.regle == "R09" }
        assertTrue(r09 != null)
        assertEquals(StatutCheck.NON_CONFORME, r09.statut,
            "R09 doit detecter les ICE differents meme quand seuls les zeros initiaux divergent")
    }

    // ----- PR #2b : conformite reglementaire MA (R25 R26 R27 R30 R06b R18-split) -----

    private fun newMarche(reference: String = "MARCHE-${System.nanoTime()}"): EngagementMarche {
        val m = EngagementMarche().apply { this.reference = reference }
        return marcheRepo.saveAndFlush(m)
    }

    @Test
    fun `golden 25 R30 NON_CONFORME si taux TVA hors liste legale (CGI art 87-100)`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        dossier.documents.add(fDoc)
        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 3, 15)
            montantHt = BigDecimal("1000"); montantTva = BigDecimal("180"); montantTtc = BigDecimal("1180")
            tauxTva = BigDecimal("18") // hors {0,7,10,14,20}
            fournisseur = "X"; ice = "001509176000008"; identifiantFiscal = "IF-1"
        })
        dossierRepo.save(dossier)

        val r30 = validationEngine.validate(dossier).first { it.regle == "R30" }
        assertEquals(StatutCheck.NON_CONFORME, r30.statut,
            "Taux 18% n'est pas dans la liste legale CGI {0, 7, 10, 14, 20}")
    }

    @Test
    fun `golden 26 R30 CONFORME pour taux TVA 20 pourcent`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        dossier.documents.add(fDoc)
        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-2"; dateFacture = LocalDate.of(2026, 3, 15)
            montantHt = BigDecimal("1000"); montantTva = BigDecimal("200"); montantTtc = BigDecimal("1200")
            tauxTva = BigDecimal("20")
            fournisseur = "X"; ice = "001509176000008"; identifiantFiscal = "IF-1"
        })
        dossierRepo.save(dossier)

        val r30 = validationEngine.validate(dossier).first { it.regle == "R30" }
        assertEquals(StatutCheck.CONFORME, r30.statut, "20% est le taux normal CGI art. 88")
    }

    @Test
    fun `golden 27 R06b NON_CONFORME si TVA_SOURCE declaree a 50 pourcent au lieu de 75`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(fDoc, opDoc))
        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 3, 1)
            montantTtc = BigDecimal("120000"); fournisseur = "X"
        })
        val op = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 3, 15)
            montantOperation = BigDecimal("100000")
        }
        op.retenues.add(Retenue(ordrePaiement = op, type = TypeRetenue.TVA_SOURCE).apply {
            base = BigDecimal("20000"); taux = BigDecimal("50") // taux legal = 75 %
            montant = BigDecimal("10000")
        })
        dossier.ordrePaiement = op
        dossierRepo.save(dossier)

        val r06b = validationEngine.validate(dossier).first { it.regle == "R06b" }
        assertEquals(StatutCheck.NON_CONFORME, r06b.statut,
            "Taux TVA_SOURCE legal = 75% (CGI art. 117), declare 50% doit etre NOK")
    }

    @Test
    fun `golden 28 R06b CONFORME pour IS_HONORAIRES a 10 pourcent (CGI art 73-II-G)`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(fDoc, opDoc))
        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-1"; dateFacture = LocalDate.of(2026, 3, 1)
            montantTtc = BigDecimal("12000"); fournisseur = "X"
        })
        val op = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 3, 15)
            montantOperation = BigDecimal("10800")
        }
        op.retenues.add(Retenue(ordrePaiement = op, type = TypeRetenue.IS_HONORAIRES).apply {
            base = BigDecimal("12000"); taux = BigDecimal("10")
            montant = BigDecimal("1200")
        })
        dossier.ordrePaiement = op
        dossierRepo.save(dossier)

        val r06b = validationEngine.validate(dossier).first { it.regle == "R06b" }
        assertEquals(StatutCheck.CONFORME, r06b.statut, "IR honoraires legal = 10% CGI art. 73-II-G")
    }

    @Test
    fun `golden 29 R18 marche public NON_CONFORME si attestation au-dela de 3 mois`() {
        val marche = newMarche()
        val dossier = newDossier()
        dossier.engagement = marche
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc, opDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.of(2026, 3, 15); fournisseur = "X"
            ice = "001509176000008"; identifiantFiscal = "IF-1"
        })
        // OP du 2026-04-15, attestation editee 4 mois avant : valide B2B (6 mo)
        // mais NOK pour marche public (3 mo).
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.of(2025, 12, 15)
            raisonSociale = "X"; ice = "001509176000008"; identifiantFiscal = "IF-1"
        }
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 4, 15)
            montantOperation = BigDecimal("1000")
        }
        dossierRepo.save(dossier)

        val r18 = validationEngine.validate(dossier).first { it.regle == "R18" }
        assertEquals(StatutCheck.NON_CONFORME, r18.statut,
            "R18 marche public : 3 mois max (Circulaire DGI 717) — attestation 4 mois doit etre NOK")
    }

    @Test
    fun `golden 30 R18 B2B CONFORME pour attestation a 4 mois (regle 6 mois s'applique)`() {
        // Pas d'engagement Marche : on tombe sur la regle B2B 6 mois.
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        val arfDoc = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(fDoc, arfDoc, opDoc))

        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            dateFacture = LocalDate.of(2026, 3, 15); fournisseur = "X"
            ice = "001509176000008"; identifiantFiscal = "IF-1"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = arfDoc).apply {
            dateEdition = LocalDate.of(2025, 12, 15)
            raisonSociale = "X"; ice = "001509176000008"; identifiantFiscal = "IF-1"
        }
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 4, 15)
            montantOperation = BigDecimal("1000")
        }
        dossierRepo.save(dossier)

        val r18 = validationEngine.validate(dossier).first { it.regle == "R18" }
        assertEquals(StatutCheck.CONFORME, r18.statut,
            "R18 B2B : attestation 4 mois <= 6 mois doit etre CONFORME")
    }

    @Test
    fun `golden 31 R25 NON_CONFORME si OP marche public emis plus de 60 jours apres reception`() {
        val marche = newMarche()
        val dossier = newDossier(DossierType.CONTRACTUEL)
        dossier.engagement = marche
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        val pvDoc = doc(dossier, TypeDocument.PV_RECEPTION, "pv.pdf")
        dossier.documents.addAll(listOf(opDoc, pvDoc))
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 4, 30)
            montantOperation = BigDecimal("100000")
        }
        dossier.pvReception = PvReception(dossier = dossier, document = pvDoc).apply {
            dateReception = LocalDate.of(2026, 1, 15) // 105 jours avant l'OP
        }
        dossierRepo.save(dossier)

        val r25 = validationEngine.validate(dossier).first { it.regle == "R25" }
        assertEquals(StatutCheck.NON_CONFORME, r25.statut,
            "Decret 2-22-431 art. 159 : delai > 60 j sur marche public doit etre NOK")
    }

    @Test
    fun `golden 32 R25 CONFORME si OP marche public emis dans les 60 jours`() {
        val marche = newMarche()
        val dossier = newDossier(DossierType.CONTRACTUEL)
        dossier.engagement = marche
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        val pvDoc = doc(dossier, TypeDocument.PV_RECEPTION, "pv.pdf")
        dossier.documents.addAll(listOf(opDoc, pvDoc))
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 3, 1)
            montantOperation = BigDecimal("100000")
        }
        dossier.pvReception = PvReception(dossier = dossier, document = pvDoc).apply {
            dateReception = LocalDate.of(2026, 1, 15) // 45 jours avant l'OP
        }
        dossierRepo.save(dossier)

        val r25 = validationEngine.validate(dossier).first { it.regle == "R25" }
        assertEquals(StatutCheck.CONFORME, r25.statut, "45j <= 60j : conforme")
    }

    @Test
    fun `golden 33 R26 NON_CONFORME si paiement especes superieur a 5000 MAD (CGI art 193-ter)`() {
        val dossier = newDossier()
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.add(opDoc)
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-1"; dateEmission = LocalDate.of(2026, 3, 1)
            montantOperation = BigDecimal("12000")
            natureOperation = "Reglement en especes"
        }
        dossierRepo.save(dossier)

        val r26 = validationEngine.validate(dossier).first { it.regle == "R26" }
        assertEquals(StatutCheck.NON_CONFORME, r26.statut,
            "Paiement especes 12000 MAD > plafond legal 5000 (CGI art. 193-ter)")
    }

    @Test
    fun `golden 34 R26 CONFORME pour paiement especes inferieur a 5000 MAD`() {
        val dossier = newDossier()
        val opDoc = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.add(opDoc)
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = opDoc).apply {
            numeroOp = "OP-2"; dateEmission = LocalDate.of(2026, 3, 1)
            montantOperation = BigDecimal("3000")
            natureOperation = "Reglement comptant"
        }
        dossierRepo.save(dossier)

        val r26 = validationEngine.validate(dossier).first { it.regle == "R26" }
        assertEquals(StatutCheck.CONFORME, r26.statut, "3000 MAD <= 5000 : conforme")
    }

    @Test
    fun `golden 35 R27 NON_CONFORME si devise EUR sur facture marocaine`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        // On simule un champ devise extrait via donneesExtraites JSON.
        fDoc.donneesExtraites = mapOf(
            "fournisseur" to "X",
            "devise" to "EUR"
        )
        dossier.documents.add(fDoc)
        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-EUR"; dateFacture = LocalDate.of(2026, 3, 15)
            montantTtc = BigDecimal("1000"); fournisseur = "X"
            ice = "001509176000008"; identifiantFiscal = "IF-1"
        })
        dossierRepo.save(dossier)

        val r27 = validationEngine.validate(dossier).first { it.regle == "R27" }
        assertEquals(StatutCheck.NON_CONFORME, r27.statut,
            "CGNC + Loi 9-88 : devise MAD obligatoire, EUR doit etre NOK")
    }

    @Test
    fun `golden 36 R27 CONFORME si devise MAD explicite`() {
        val dossier = newDossier()
        val fDoc = doc(dossier, TypeDocument.FACTURE)
        fDoc.donneesExtraites = mapOf("fournisseur" to "X", "devise" to "MAD")
        dossier.documents.add(fDoc)
        dossier.factures.add(Facture(dossier = dossier, document = fDoc).apply {
            numeroFacture = "F-MAD"; dateFacture = LocalDate.of(2026, 3, 15)
            montantTtc = BigDecimal("1000"); fournisseur = "X"
            ice = "001509176000008"; identifiantFiscal = "IF-1"
        })
        dossierRepo.save(dossier)

        val r27 = validationEngine.validate(dossier).first { it.regle == "R27" }
        assertEquals(StatutCheck.CONFORME, r27.statut, "Devise MAD explicite : CONFORME")
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
