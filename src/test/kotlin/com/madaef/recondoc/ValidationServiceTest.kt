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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ValidationServiceTest {

    @Autowired lateinit var validationEngine: ValidationEngine
    @Autowired lateinit var dossierRepo: DossierRepository

    private fun createDossier(type: DossierType = DossierType.BC): DossierPaiement {
        return dossierRepo.save(DossierPaiement(
            reference = "TEST-${System.nanoTime()}",
            type = type,
            statut = StatutDossier.BROUILLON
        ))
    }

    private fun doc(dossier: DossierPaiement, type: TypeDocument, name: String = "test.pdf") =
        Document(dossier = dossier, typeDocument = type, nomFichier = name, cheminFichier = "/tmp/$name")

    @Test
    fun `R01-R03 CONFORME when BC and Facture amounts match`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.FACTURE, "f.pdf")
        val d2 = doc(dossier, TypeDocument.BON_COMMANDE, "bc.pdf")
        dossier.documents.addAll(listOf(d1, d2))

        dossier.factures.add(Facture(dossier = dossier, document = d1).apply {
            montantHt = BigDecimal("1496.66"); montantTva = BigDecimal("299.34"); montantTtc = BigDecimal("1796.00"); tauxTva = BigDecimal("20")
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = d2).apply {
            montantHt = BigDecimal("1496.66"); montantTva = BigDecimal("299.34"); montantTtc = BigDecimal("1796.00"); tauxTva = BigDecimal("20")
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R01" }.statut)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R02" }.statut)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R03" }.statut)
    }

    @Test
    fun `R01 AVERTISSEMENT when Facture is 1-3 of BC (partial coverage)`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.FACTURE)
        val d2 = doc(dossier, TypeDocument.BON_COMMANDE, "bc.pdf")
        dossier.documents.addAll(listOf(d1, d2))

        dossier.factures.add(Facture(dossier = dossier, document = d1).apply {
            montantTtc = BigDecimal("7200.00")
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = d2).apply {
            montantTtc = BigDecimal("21600.00")
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r01 = results.first { it.regle == "R01" }
        assertEquals(StatutCheck.AVERTISSEMENT, r01.statut)
        assertTrue(r01.detail!!.contains("1/3"))
    }

    @Test
    fun `R03b warns when TVA rates differ`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.FACTURE)
        val d2 = doc(dossier, TypeDocument.BON_COMMANDE, "bc.pdf")
        dossier.documents.addAll(listOf(d1, d2))

        dossier.factures.add(Facture(dossier = dossier, document = d1).apply {
            montantTva = BigDecimal("812.00"); tauxTva = BigDecimal("20"); montantTtc = BigDecimal("5028.62")
        })
        dossier.bonCommande = BonCommande(dossier = dossier, document = d2).apply {
            montantTva = BigDecimal("812.00"); tauxTva = BigDecimal("10"); montantTtc = BigDecimal("5028.62")
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r03b = results.firstOrNull { it.regle == "R03b" }
        assertTrue(r03b != null, "R03b should fire when TVA rates differ")
        assertEquals(StatutCheck.AVERTISSEMENT, r03b.statut)
    }

    @Test
    fun `R09-R10 CONFORME with leading zeros in IF`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.FACTURE)
        val d2 = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.addAll(listOf(d1, d2))

        dossier.factures.add(Facture(dossier = dossier, document = d1).apply {
            ice = "001509176000008"; identifiantFiscal = "03302870"
        })
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = d2).apply {
            ice = "001509176000008"; identifiantFiscal = "3302870"
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R09" }.statut)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R10" }.statut, "IF should match despite leading zero")
    }

    @Test
    fun `R04 CONFORME when OP equals TTC without retenues`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.FACTURE)
        val d2 = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(d1, d2))

        dossier.factures.add(Facture(dossier = dossier, document = d1).apply { montantTtc = BigDecimal("1796.00") })
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = d2).apply { montantOperation = BigDecimal("1796.00") }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R04" }.statut)
    }

    @Test
    fun `R05 CONFORME when OP equals TTC minus retenues`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.FACTURE)
        val d2 = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(d1, d2))

        dossier.factures.add(Facture(dossier = dossier, document = d1).apply {
            montantHt = BigDecimal("359601.00"); montantTtc = BigDecimal("431521.20")
        })
        val op = OrdrePaiement(dossier = dossier, document = d2).apply { montantOperation = BigDecimal("359601.00") }
        op.retenues.add(Retenue(ordrePaiement = op, type = TypeRetenue.TVA_SOURCE).apply {
            base = BigDecimal("71920.20"); taux = BigDecimal("75"); montant = BigDecimal("53940.15")
        })
        op.retenues.add(Retenue(ordrePaiement = op, type = TypeRetenue.IS_HONORAIRES).apply {
            base = BigDecimal("359601.00"); taux = BigDecimal("5"); montant = BigDecimal("17980.05")
        })
        dossier.ordrePaiement = op
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R05" }.statut)
    }

    @Test
    fun `R15 CONFORME when grille x months matches facture HT`() {
        val dossier = createDossier(DossierType.CONTRACTUEL)
        val d1 = doc(dossier, TypeDocument.FACTURE)
        val d2 = doc(dossier, TypeDocument.CONTRAT_AVENANT, "c.pdf")
        val d3 = doc(dossier, TypeDocument.PV_RECEPTION, "pv.pdf")
        dossier.documents.addAll(listOf(d1, d2, d3))

        dossier.factures.add(Facture(dossier = dossier, document = d1).apply { montantHt = BigDecimal("359601.00") })

        val contrat = ContratAvenant(dossier = dossier, document = d2).apply { referenceContrat = "CF SIE000777" }
        val prices = listOf("41370", "4441", "25428", "906", "21398", "19217", "7107")
        prices.forEachIndexed { i, p ->
            contrat.grillesTarifaires.add(GrilleTarifaire(contratAvenant = contrat, designation = "Prestation $i").apply {
                prixUnitaireHt = BigDecimal(p); periodicite = Periodicite.MENSUEL
            })
        }
        dossier.contratAvenant = contrat

        dossier.pvReception = PvReception(dossier = dossier, document = d3).apply {
            periodeDebut = LocalDate.of(2025, 7, 1); periodeFin = LocalDate.of(2025, 9, 30)
        }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        val r15 = results.first { it.regle == "R15" }
        assertEquals(StatutCheck.CONFORME, r15.statut, "Grille x 3 months = HT: ${r15.detail}")
    }

    @Test
    fun `R19 CONFORME when QR code matches printed verification code on canonical DGI host`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.add(d1)
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = d1).apply {
            codeVerification = "18a50bf6baf372bd"
            qrPayload = "https://attestation.tax.gov.ma/verify?code=18a50bf6baf372bd"
            qrCodeExtrait = "18a50bf6baf372bd"
            qrHost = "attestation.tax.gov.ma"
        }
        dossierRepo.save(dossier)

        val r19 = validationEngine.validate(dossier).first { it.regle == "R19" }
        assertEquals(StatutCheck.CONFORME, r19.statut, r19.detail)
    }

    @Test
    fun `R19 NON_CONFORME when QR code differs from printed code`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.add(d1)
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = d1).apply {
            codeVerification = "18a50bf6baf372bd"
            qrPayload = "https://www.tax.gov.ma/verify?code=deadbeefcafebabe"
            qrCodeExtrait = "deadbeefcafebabe"
            qrHost = "www.tax.gov.ma"
        }
        dossierRepo.save(dossier)

        val r19 = validationEngine.validate(dossier).first { it.regle == "R19" }
        assertEquals(StatutCheck.NON_CONFORME, r19.statut, r19.detail)
    }

    @Test
    fun `R19 NON_CONFORME when no QR code was decoded`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.add(d1)
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = d1).apply {
            codeVerification = "18a50bf6baf372bd"
            qrScanError = "Aucun QR code lisible"
        }
        dossierRepo.save(dossier)

        val r19 = validationEngine.validate(dossier).first { it.regle == "R19" }
        assertEquals(StatutCheck.NON_CONFORME, r19.statut, r19.detail)
    }

    @Test
    fun `R19 NON_CONFORME when QR host is not tax gov ma`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.add(d1)
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = d1).apply {
            codeVerification = "18a50bf6baf372bd"
            qrPayload = "https://phishing.example.com/?code=18a50bf6baf372bd"
            qrCodeExtrait = "18a50bf6baf372bd"
            qrHost = "phishing.example.com"
        }
        dossierRepo.save(dossier)

        val r19 = validationEngine.validate(dossier).first { it.regle == "R19" }
        assertEquals(StatutCheck.NON_CONFORME, r19.statut, r19.detail)
        assertTrue(r19.detail!!.contains("attestation.tax.gov.ma"), "Le detail doit pointer vers le site officiel: ${r19.detail}")
    }

    @Test
    fun `R19 NON_CONFORME when QR payload uses javascript scheme`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.add(d1)
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = d1).apply {
            codeVerification = "18a50bf6baf372bd"
            qrPayload = "javascript:alert('xss')"
            qrCodeExtrait = null
            qrHost = null
        }
        dossierRepo.save(dossier)

        val r19 = validationEngine.validate(dossier).first { it.regle == "R19" }
        assertEquals(StatutCheck.NON_CONFORME, r19.statut, r19.detail)
        assertTrue(r19.detail!!.contains("dangereux") || r19.detail!!.contains("Schema"), "Le detail doit signaler le danger: ${r19.detail}")
    }

    @Test
    fun `R19 AVERTISSEMENT when QR is on tax gov ma but not attestation subdomain`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.add(d1)
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = d1).apply {
            codeVerification = "18a50bf6baf372bd"
            qrPayload = "https://www.tax.gov.ma/verify?code=18a50bf6baf372bd"
            qrCodeExtrait = "18a50bf6baf372bd"
            qrHost = "www.tax.gov.ma"
        }
        dossierRepo.save(dossier)

        val r19 = validationEngine.validate(dossier).first { it.regle == "R19" }
        // www.tax.gov.ma est officiel mais pas canonique — warning, pas blocage.
        assertEquals(StatutCheck.AVERTISSEMENT, r19.statut, r19.detail)
    }

    @Test
    fun `R23 NON_CONFORME when attestation says estEnRegle false`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.add(d1)
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = d1).apply {
            estEnRegle = false
        }
        dossierRepo.save(dossier)

        val r23 = validationEngine.validate(dossier).first { it.regle == "R23" }
        assertEquals(StatutCheck.NON_CONFORME, r23.statut, r23.detail)
    }

    @Test
    fun `R23 CONFORME when attestation confirms regularity`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.ATTESTATION_FISCALE, "arf.pdf")
        dossier.documents.add(d1)
        dossier.attestationFiscale = AttestationFiscale(dossier = dossier, document = d1).apply {
            estEnRegle = true
        }
        dossierRepo.save(dossier)

        val r23 = validationEngine.validate(dossier).first { it.regle == "R23" }
        assertEquals(StatutCheck.CONFORME, r23.statut, r23.detail)
    }

    @Test
    fun `R11 CONFORME when RIB matches with different spacing`() {
        val dossier = createDossier()
        val d1 = doc(dossier, TypeDocument.FACTURE)
        val d2 = doc(dossier, TypeDocument.ORDRE_PAIEMENT, "op.pdf")
        dossier.documents.addAll(listOf(d1, d2))

        dossier.factures.add(Facture(dossier = dossier, document = d1).apply { rib = "022 810 0001500027756378 23" })
        dossier.ordrePaiement = OrdrePaiement(dossier = dossier, document = d2).apply { rib = "022 810 000 150 002 775 637 823" }
        dossierRepo.save(dossier)

        val results = validationEngine.validate(dossier)
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R11" }.statut, "RIB should match after stripping spaces")
    }
}
