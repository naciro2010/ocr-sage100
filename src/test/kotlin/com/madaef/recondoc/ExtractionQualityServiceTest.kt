package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.DossierType
import com.madaef.recondoc.entity.dossier.StatutDossier
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.extraction.ExtractionQualityService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractionQualityServiceTest {

    private val service = ExtractionQualityService()

    private fun buildDossier() = DossierPaiement(
        reference = "TEST-001",
        type = DossierType.BC,
        statut = StatutDossier.BROUILLON
    )

    private fun buildFacture(data: Map<String, Any?>, confOcr: Double = 90.0, confExtract: Double = 0.9): Document =
        buildDocument(TypeDocument.FACTURE, data, confOcr, confExtract)

    private fun buildDocument(
        type: TypeDocument,
        data: Map<String, Any?>,
        confOcr: Double = 90.0,
        confExtract: Double = 0.9
    ): Document {
        val doc = Document(
            dossier = buildDossier(),
            typeDocument = type,
            nomFichier = "doc.pdf",
            cheminFichier = "/tmp/doc.pdf"
        )
        doc.ocrConfidence = confOcr
        doc.extractionConfidence = confExtract
        doc.donneesExtraites = data
        return doc
    }

    @Test
    fun `facture complete gets high score`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-2026-001",
            "dateFacture" to "2026-03-15",
            "montantTTC" to 12000.0,
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "tauxTVA" to 20,
            "fournisseur" to "ACME SARL",
            "ice" to "123456789012345",
            "identifiantFiscal" to "IF001",
            "rib" to "0000000000000000",
            "lignes" to listOf(mapOf("designation" to "Service", "montantTotalHT" to 10000))
        ))
        val r = service.evaluate(doc)
        assertTrue(r.score >= 85, "score should be >= 85, got ${r.score}")
        assertEquals(emptyList(), r.missingMandatory)
        assertTrue(r.coherenceArithmetique >= 0.99, "arith HT+TVA=TTC should match")
    }

    @Test
    fun `facture without mandatory fields gets low score`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-1",
            "montantTTC" to 1000.0
        ), confOcr = 60.0, confExtract = 0.6)
        val r = service.evaluate(doc)
        assertTrue(r.score < 60, "score should be <60, got ${r.score}")
        assertTrue("dateFacture" in r.missingMandatory)
        assertTrue("fournisseur" in r.missingMandatory)
        assertTrue("ice" in r.missingMandatory)
    }

    @Test
    fun `arithmetic inconsistency drops coherence score`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-1",
            "dateFacture" to "2026-01-01",
            "montantTTC" to 15000.0,
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "fournisseur" to "X",
            "ice" to "1"
        ))
        val r = service.evaluate(doc)
        assertTrue(r.coherenceArithmetique < 1.0, "coherence should be <1 on HT+TVA != TTC")
    }

    @Test
    fun `blank string field counts as missing`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "",
            "dateFacture" to "2026-01-01",
            "montantTTC" to 1000.0,
            "fournisseur" to "X",
            "ice" to "1"
        ))
        val r = service.evaluate(doc)
        assertTrue("numeroFacture" in r.missingMandatory)
    }

    @Test
    fun `applyTo persists score and missing fields on document`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-1",
            "montantTTC" to 100.0
        ))
        service.applyTo(doc)
        assertTrue(doc.extractionQualityScore != null)
        assertTrue(doc.missingMandatoryFields != null)
        assertTrue(doc.missingMandatoryFields!!.contains("fournisseur"))
    }

    @Test
    fun `case insensitive field matching`() {
        val doc = buildFacture(mapOf(
            "NumeroFacture" to "F-1",
            "DateFacture" to "2026-01-01",
            "MontantTTC" to 100.0,
            "Fournisseur" to "X",
            "ICE" to "1"
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory)
    }

    // --- Alignement MandatoryFields <-> cles emises par les prompts ---
    // Un champ mandatory qui n'est jamais emis par le prompt genere un faux
    // "missing" et declenche une re-extraction inutile. Ces tests verrouillent
    // l'alignement pour tous les types de documents.

    @Test
    fun `BC complet utilise les cles reference et dateBc du prompt`() {
        val doc = buildDocument(TypeDocument.BON_COMMANDE, mapOf(
            "reference" to "CF SIE 2026-1234",
            "dateBc" to "2026-02-10",
            "fournisseur" to "ACME SARL",
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "tauxTVA" to 20,
            "montantTTC" to 12000.0,
            "objet" to "Prestation de service",
            "signataire" to "J. Dupont",
            "lignes" to listOf(mapOf("designation" to "L1", "montantLigneHT" to 10000))
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory, "BC complet ne doit avoir aucun mandatory missing")
        assertEquals(emptyList(), r.missingImportant, "BC complet ne doit avoir aucun important missing")
    }

    @Test
    fun `OP complet utilise les cles numeroOp dateEmission beneficiaire du prompt`() {
        val doc = buildDocument(TypeDocument.ORDRE_PAIEMENT, mapOf(
            "numeroOp" to "OP-2026-001",
            "dateEmission" to "2026-03-01",
            "beneficiaire" to "ACME SARL",
            "rib" to "000000000000000000000000",
            "montantOperation" to 11500.0,
            "referenceFacture" to "F-2026-001",
            "banque" to "BMCE",
            "syntheseControleur" to mapOf("netAPayer" to 11500.0),
            "retenues" to listOf(mapOf("type" to "TVA_SOURCE", "montant" to 500))
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory, "OP complet ne doit avoir aucun mandatory missing")
    }

    @Test
    fun `CONTRAT complet utilise les cles referenceContrat dateSignature parties du prompt`() {
        val doc = buildDocument(TypeDocument.CONTRAT_AVENANT, mapOf(
            "referenceContrat" to "C-2026-001",
            "dateSignature" to "2025-12-01",
            "parties" to listOf("MADAEF", "ACME SARL"),
            "objet" to "Entretien espaces verts",
            "dateEffet" to "2026-01-01",
            "grillesTarifaires" to listOf(mapOf("designation" to "Prestation", "prixUnitaireHT" to 1000)),
            "numeroAvenant" to null
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory, "Contrat complet ne doit avoir aucun mandatory missing")
    }

    @Test
    fun `ATTESTATION complet utilise les cles numero dateEdition raisonSociale du prompt`() {
        val doc = buildDocument(TypeDocument.ATTESTATION_FISCALE, mapOf(
            "numero" to "2140-2026-798",
            "dateEdition" to "2026-02-15",
            "raisonSociale" to "ACME SARL",
            "ice" to "001234567000089",
            "identifiantFiscal" to "12345678",
            "rc" to "123456",
            "estEnRegle" to true,
            "codeVerification" to "18a50bf6baf372bd",
            // Champ promu en IMPORTANT par PR #2d (R18 nuance la duree de
            // validite selon le type — Circulaire DGI 717).
            "typeAttestation" to "REGULARITE_FISCALE"
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory, "Attestation complete ne doit avoir aucun mandatory missing")
        assertEquals(emptyList(), r.missingImportant)
    }

    @Test
    fun `PV_RECEPTION complet utilise les cles dateReception referenceContrat prestations`() {
        val doc = buildDocument(TypeDocument.PV_RECEPTION, mapOf(
            "titre" to "PV de reception",
            "dateReception" to "2026-03-10",
            "referenceContrat" to "C-2026-001",
            "periodeDebut" to "2026-02-01",
            "periodeFin" to "2026-02-28",
            "prestations" to listOf("Entretien janvier"),
            "signataireMadaef" to "A. Alami",
            "signataireFournisseur" to "B. Benali"
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory)
        assertEquals(emptyList(), r.missingImportant)
    }

    @Test
    fun `CHECKLIST_AUTOCONTROLE complet utilise points referenceFacture prestataire`() {
        val doc = buildDocument(TypeDocument.CHECKLIST_AUTOCONTROLE, mapOf(
            "reference" to "CCF-EN-04-V02",
            "nomProjet" to "Entretien golf",
            "referenceFacture" to "F-2026-001",
            "prestataire" to "ACME SARL",
            "points" to listOf(mapOf("numero" to 1, "estValide" to true)),
            "signataires" to listOf(mapOf("nom" to "A. Alami", "aSignature" to true)),
            "dateEtablissement" to "2026-03-12",
            "referenceBc" to "CF SIE 2026-1234"
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory)
    }

    @Test
    fun `TABLEAU_CONTROLE complet utilise points referenceFacture fournisseur`() {
        val doc = buildDocument(TypeDocument.TABLEAU_CONTROLE, mapOf(
            "societeGeree" to "MADAEF",
            "referenceFacture" to "F-2026-001",
            "fournisseur" to "ACME SARL",
            "points" to listOf(mapOf("numero" to 1, "observation" to "Conforme")),
            "signataire" to "C. Controleur",
            "dateControle" to "2026-03-15",
            "conclusionGenerale" to "Favorable"
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory)
    }

    @Test
    fun `BC sans reference ou dateBc est marque incomplet`() {
        val doc = buildDocument(TypeDocument.BON_COMMANDE, mapOf(
            "fournisseur" to "ACME SARL",
            "montantTTC" to 12000.0
        ))
        val r = service.evaluate(doc)
        assertTrue("reference" in r.missingMandatory, "reference doit etre detecte comme missing")
        assertTrue("dateBc" in r.missingMandatory, "dateBc doit etre detecte comme missing")
    }

    // --- Anti auto-tromperie : penaliser _confidence haute contredite par les faits ---

    @Test
    fun `confidence haute avec 2+ champs obligatoires manquants est penalisee a 0_5`() {
        // Claude a declare _confidence=0.95 alors que 3 champs obligatoires
        // (dateFacture, fournisseur, ice) sont null : signal d'auto-validation
        // abusive, la confidence doit etre cappee a 0.5 pour que le score
        // composite reflete la realite.
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-1",
            "montantTTC" to 1000.0
        ), confOcr = 90.0, confExtract = 0.95)
        val r = service.evaluate(doc)
        assertTrue(r.confidenceExtraction <= 0.5,
            "confidence auto-declaree doit etre cappee quand plusieurs obligatoires manquent, got ${r.confidenceExtraction}")
    }

    @Test
    fun `confidence haute coherente avec completude n'est pas penalisee`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-2026-001",
            "dateFacture" to "2026-03-15",
            "montantTTC" to 12000.0,
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "tauxTVA" to 20,
            "fournisseur" to "ACME SARL",
            "ice" to "123456789012345"
        ), confOcr = 90.0, confExtract = 0.95)
        val r = service.evaluate(doc)
        assertEquals(0.95, r.confidenceExtraction, "confidence haute legitime ne doit pas etre penalisee")
    }

    @Test
    fun `confidence haute sur facture avec arithmetique HT TVA TTC incoherente est penalisee`() {
        // coherence arith < 0.7 doit suffire a plafonner la confidence meme
        // si tous les champs obligatoires sont presents.
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-1",
            "dateFacture" to "2026-01-01",
            "montantTTC" to 20000.0, // incoherent
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "fournisseur" to "ACME",
            "ice" to "001509176000008"
        ), confOcr = 90.0, confExtract = 0.92)
        val r = service.evaluate(doc)
        assertTrue(r.confidenceExtraction <= 0.5,
            "confidence auto-declaree doit etre cappee si coherence arith est cassee, got ${r.confidenceExtraction}")
    }

    @Test
    fun `OP sans numeroOp ou dateEmission est marque incomplet`() {
        val doc = buildDocument(TypeDocument.ORDRE_PAIEMENT, mapOf(
            "rib" to "000000000000000000000000",
            "montantOperation" to 10000.0,
            "beneficiaire" to "X"
        ))
        val r = service.evaluate(doc)
        assertTrue("numeroOp" in r.missingMandatory)
        assertTrue("dateEmission" in r.missingMandatory)
    }

    // --- Couche engagement : MARCHE, BC_CADRE, CONTRAT_CADRE (Maroc) ---

    @Test
    fun `MARCHE complet utilise les cles reference objet fournisseur montantTtc dateDocument`() {
        val doc = buildDocument(TypeDocument.MARCHE, mapOf(
            "reference" to "M-2024-001",
            "objet" to "Travaux d'entretien du golf royal",
            "fournisseur" to "ACME BTP SARL",
            "montantTtc" to 1200000.00,
            "montantHt" to 1000000.00,
            "montantTva" to 200000.00,
            "tauxTva" to 20,
            "dateDocument" to "2024-06-15",
            "categorie" to "TRAVAUX",
            "delaiExecutionMois" to 12,
            "retenueGarantiePct" to 7,
            "cautionDefinitivePct" to 3,
            "numeroAo" to "AO 2024/15",
            "dateAo" to "2024-05-20",
            "revisionPrixAutorisee" to true
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory, "MARCHE complet ne doit avoir aucun mandatory missing")
    }

    @Test
    fun `BON_COMMANDE_CADRE complet utilise les cles du prompt`() {
        val doc = buildDocument(TypeDocument.BON_COMMANDE_CADRE, mapOf(
            "reference" to "BCC-2024-001",
            "objet" to "Fournitures de bureau",
            "fournisseur" to "ACME SARL",
            "montantTtc" to 500000.00,
            "montantHt" to 416666.67,
            "tauxTva" to 20,
            "dateDocument" to "2024-03-10",
            "plafondMontant" to 500000.00,
            "dateValiditeFin" to "2026-03-10",
            "seuilAntiFractionnement" to 200000.00
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory, "BC cadre complet ne doit avoir aucun mandatory missing")
    }

    @Test
    fun `CONTRAT_CADRE complet utilise les cles du prompt`() {
        val doc = buildDocument(TypeDocument.CONTRAT_CADRE, mapOf(
            "reference" to "CM-2024-015",
            "objet" to "Contrat de maintenance climatisation",
            "fournisseur" to "ACME SARL",
            "montantTtc" to 120000.00,
            "montantHt" to 100000.00,
            "tauxTva" to 20,
            "dateDocument" to "2024-01-15",
            "dateDebut" to "2024-02-01",
            "dateFin" to "2026-01-31",
            "periodicite" to "MENSUEL",
            "reconductionTacite" to true,
            "preavisResiliationJours" to 90
        ))
        val r = service.evaluate(doc)
        assertEquals(emptyList(), r.missingMandatory, "Contrat cadre complet ne doit avoir aucun mandatory missing")
    }

    // --- Coherence somme(lignes) ≈ montantHT (facture + BC) ---
    // Evite les faux negatifs R01/R03/R16 : un total HT correct mais des lignes
    // mal lues (ou l'inverse) doit deprimer la coherence pour declencher la
    // re-extraction automatique (score < 60) avant que la validation metier
    // ne compare ce total a un autre document.

    @Test
    fun `facture avec lignes coherentes avec le total a coherence pleine`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-2026-010",
            "dateFacture" to "2026-03-10",
            "fournisseur" to "ACME SARL",
            "ice" to "001234567890123",
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "montantTTC" to 12000.0,
            "tauxTVA" to 20,
            "lignes" to listOf(
                mapOf("designation" to "L1", "quantite" to 1, "prixUnitaireHT" to 6000, "montantTotalHT" to 6000),
                mapOf("designation" to "L2", "quantite" to 2, "prixUnitaireHT" to 2000, "montantTotalHT" to 4000)
            )
        ))
        val r = service.evaluate(doc)
        assertTrue(r.coherenceArithmetique >= 0.99,
            "HT+TVA=TTC ET somme lignes = HT -> coherence 1.0, got ${r.coherenceArithmetique}")
    }

    @Test
    fun `facture avec somme des lignes incoherente avec montantHT deprime la coherence`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-2026-011",
            "dateFacture" to "2026-03-10",
            "fournisseur" to "ACME SARL",
            "ice" to "001234567890123",
            "montantHT" to 12000.0,
            "montantTVA" to 2400.0,
            "montantTTC" to 14400.0,
            "tauxTVA" to 20,
            "lignes" to listOf(
                mapOf("designation" to "L1", "montantTotalHT" to 6000),
                mapOf("designation" to "L2", "montantTotalHT" to 3800)
            )
        ))
        val r = service.evaluate(doc)
        assertTrue(r.coherenceArithmetique < 0.9,
            "sum(lignes)=9800 != montantHT=12000 (ecart 18%) doit deprimer coherence, got ${r.coherenceArithmetique}")
    }

    @Test
    fun `facture sans lignes garde coherence basee sur HT TVA TTC seulement`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-2026-012",
            "dateFacture" to "2026-03-10",
            "fournisseur" to "ACME SARL",
            "ice" to "001234567890123",
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "montantTTC" to 12000.0,
            "tauxTVA" to 20
        ))
        val r = service.evaluate(doc)
        assertTrue(r.coherenceArithmetique >= 0.99,
            "pas de lignes -> coherence = HT+TVA=TTC uniquement, got ${r.coherenceArithmetique}")
    }

    @Test
    fun `BC avec lignes coherentes avec le total a coherence pleine`() {
        val doc = buildDocument(TypeDocument.BON_COMMANDE, mapOf(
            "reference" to "CF SIE 2026-0100",
            "dateBc" to "2026-02-15",
            "fournisseur" to "ACME SARL",
            "objet" to "Prestation",
            "montantHT" to 15000.0,
            "montantTVA" to 3000.0,
            "montantTTC" to 18000.0,
            "tauxTVA" to 20,
            "signataire" to "X",
            "lignes" to listOf(
                mapOf("designation" to "L1", "quantite" to 3, "prixUnitaireHT" to 5000, "montantLigneHT" to 15000)
            )
        ))
        val r = service.evaluate(doc)
        assertTrue(r.coherenceArithmetique >= 0.99,
            "BC coherent doit avoir coherence = 1, got ${r.coherenceArithmetique}")
    }

    @Test
    fun `BC avec somme des lignes incoherente deprime la coherence`() {
        val doc = buildDocument(TypeDocument.BON_COMMANDE, mapOf(
            "reference" to "CF SIE 2026-0101",
            "dateBc" to "2026-02-15",
            "fournisseur" to "ACME SARL",
            "objet" to "Prestation",
            "montantHT" to 15000.0,
            "montantTVA" to 3000.0,
            "montantTTC" to 18000.0,
            "tauxTVA" to 20,
            "signataire" to "X",
            "lignes" to listOf(
                mapOf("designation" to "L1", "montantLigneHT" to 4000),
                mapOf("designation" to "L2", "montantLigneHT" to 5000)
            )
        ))
        val r = service.evaluate(doc)
        assertTrue(r.coherenceArithmetique < 0.9,
            "BC sum(lignes)=9000 != montantHT=15000 doit deprimer coherence, got ${r.coherenceArithmetique}")
    }

    @Test
    fun `BC sans lignes garde coherence basee sur HT TVA TTC`() {
        val doc = buildDocument(TypeDocument.BON_COMMANDE, mapOf(
            "reference" to "CF SIE 2026-0102",
            "dateBc" to "2026-02-15",
            "fournisseur" to "ACME SARL",
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "montantTTC" to 12000.0
        ))
        val r = service.evaluate(doc)
        assertTrue(r.coherenceArithmetique >= 0.99,
            "BC sans lignes -> coherence = HT+TVA=TTC, got ${r.coherenceArithmetique}")
    }

    @Test
    fun `facture avec lignes coherentes mais HT TVA TTC faux deprime coherence`() {
        val doc = buildFacture(mapOf(
            "numeroFacture" to "F-2026-013",
            "dateFacture" to "2026-03-10",
            "fournisseur" to "ACME",
            "ice" to "001234567890123",
            "montantHT" to 10000.0,
            "montantTVA" to 2000.0,
            "montantTTC" to 15000.0, // incoherent avec HT+TVA
            "tauxTVA" to 20,
            "lignes" to listOf(
                mapOf("designation" to "L1", "montantTotalHT" to 10000)
            )
        ))
        val r = service.evaluate(doc)
        assertTrue(r.coherenceArithmetique < 0.9,
            "HT+TVA=12000 != TTC=15000 doit deprimer meme si lignes coherentes, got ${r.coherenceArithmetique}")
    }

    @Test
    fun `MARCHE sans reference ou fournisseur est marque incomplet`() {
        val doc = buildDocument(TypeDocument.MARCHE, mapOf(
            "objet" to "Travaux",
            "montantTtc" to 1000000.00,
            "dateDocument" to "2024-06-15"
        ))
        val r = service.evaluate(doc)
        assertTrue("reference" in r.missingMandatory)
        assertTrue("fournisseur" in r.missingMandatory)
    }
}
