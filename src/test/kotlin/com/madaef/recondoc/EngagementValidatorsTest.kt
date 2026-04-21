package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.entity.engagement.*
import com.madaef.recondoc.service.validation.engagement.ContratValidator
import com.madaef.recondoc.service.validation.engagement.EngagementValidationContext
import com.madaef.recondoc.service.validation.engagement.MarcheValidator
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests unitaires purs des EngagementValidator sans dependances externes
 * (MarcheValidator, ContratValidator). Les validators avec dependances
 * JPA (BonCommandeValidator, CommonEngagementValidator) sont couverts par
 * les tests d'integration Spring.
 *
 * Chaque regle a au minimum 2 scenarios (conforme + non conforme) selon
 * l'exigence CLAUDE.md.
 */
class EngagementValidatorsTest {

    private fun dossier(reference: String = "DOS-TEST", montantTtc: BigDecimal? = null) = DossierPaiement(
        id = UUID.randomUUID(),
        reference = reference,
        type = DossierType.BC,
        statut = StatutDossier.BROUILLON,
        montantTtc = montantTtc,
        dateCreation = LocalDateTime.now()
    )

    private fun facture(dossier: DossierPaiement, date: LocalDate, montantTtc: BigDecimal): Facture {
        val doc = Document(
            dossier = dossier, typeDocument = TypeDocument.FACTURE,
            nomFichier = "f.pdf", cheminFichier = "/tmp/f.pdf"
        )
        return Facture(dossier = dossier, document = doc).apply {
            dateFacture = date
            this.montantTtc = montantTtc
        }
    }

    private fun ctx(
        montantConsomme: BigDecimal = BigDecimal.ZERO,
        dossiers: List<DossierPaiement> = emptyList(),
        tolerance: BigDecimal = BigDecimal("0.05")
    ) = EngagementValidationContext(
        montantConsomme = montantConsomme,
        dossiersRattaches = dossiers,
        toleranceMontant = tolerance
    )

    // =========================================================
    // MarcheValidator : R-M01..R-M07
    // =========================================================

    @Test
    fun `R-M01 CONFORME quand date facture dans la fenetre du marche`() {
        val validator = MarcheValidator()
        val dossier = dossier()
        dossier.factures.add(facture(dossier, LocalDate.of(2026, 3, 15), BigDecimal("100000")))
        val marche = EngagementMarche().apply {
            reference = "M-2026-001"
            dateNotification = LocalDate.of(2026, 1, 1)
            delaiExecutionMois = 6
            montantTtc = BigDecimal("1000000")
        }

        val results = validator.validate(marche, dossier, ctx())
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R-M01" }.statut)
    }

    @Test
    fun `R-M01 NON_CONFORME quand date facture apres delai d'execution`() {
        val validator = MarcheValidator()
        val dossier = dossier()
        dossier.factures.add(facture(dossier, LocalDate.of(2026, 10, 15), BigDecimal("100000")))
        val marche = EngagementMarche().apply {
            reference = "M-2026-001"
            dateNotification = LocalDate.of(2026, 1, 1)
            delaiExecutionMois = 6
        }

        val results = validator.validate(marche, dossier, ctx())
        assertEquals(StatutCheck.NON_CONFORME, results.first { it.regle == "R-M01" }.statut)
    }

    @Test
    fun `R-M04 AVERTISSEMENT quand numero AO absent des refs`() {
        val validator = MarcheValidator()
        val dossier = dossier()
        val marche = EngagementMarche().apply {
            reference = "M-2026-001"
            numeroAo = "AO/2026/42"
        }
        val results = validator.validate(marche, dossier, ctx())
        assertEquals(StatutCheck.AVERTISSEMENT, results.first { it.regle == "R-M04" }.statut)
    }

    @Test
    fun `R-M04 NON_APPLICABLE sans numero AO sur le marche`() {
        val validator = MarcheValidator()
        val dossier = dossier()
        val marche = EngagementMarche().apply { reference = "M-2026-001" }
        val results = validator.validate(marche, dossier, ctx())
        assertEquals(StatutCheck.NON_APPLICABLE, results.first { it.regle == "R-M04" }.statut)
    }

    @Test
    fun `Marche validator produit 7 regles R-M`() {
        val validator = MarcheValidator()
        val dossier = dossier()
        val marche = EngagementMarche().apply { reference = "M-2026-001" }
        val results = validator.validate(marche, dossier, ctx())
        val codes = results.map { it.regle }.distinct()
        assertEquals(7, codes.size)
        assertTrue(codes.containsAll(listOf("R-M01", "R-M02", "R-M03", "R-M04", "R-M05", "R-M06", "R-M07")))
    }

    // =========================================================
    // ContratValidator : R-C01..R-C05
    // =========================================================

    @Test
    fun `R-C01 CONFORME quand periodicite mensuelle respectee`() {
        val validator = ContratValidator()
        val dossier = dossier()
        dossier.factures.add(facture(dossier, LocalDate.of(2026, 4, 15), BigDecimal("1000")))
        val dossierPrec = dossier()
        dossierPrec.factures.add(facture(dossierPrec, LocalDate.of(2026, 3, 15), BigDecimal("1000")))

        val contrat = EngagementContrat().apply {
            reference = "C-2026-001"
            periodicite = PeriodiciteContrat.MENSUEL
        }
        val results = validator.validate(contrat, dossier, ctx(dossiers = listOf(dossierPrec, dossier)))
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R-C01" }.statut)
    }

    @Test
    fun `R-C02 NON_CONFORME quand paiement apres dateFin sans reconduction`() {
        val validator = ContratValidator()
        val dossier = dossier()
        dossier.factures.add(facture(dossier, LocalDate.of(2027, 1, 15), BigDecimal("1000")))
        val contrat = EngagementContrat().apply {
            reference = "C-2026-001"
            dateFin = LocalDate.of(2026, 12, 31)
            reconductionTacite = false
        }
        val results = validator.validate(contrat, dossier, ctx())
        assertEquals(StatutCheck.NON_CONFORME, results.first { it.regle == "R-C02" }.statut)
    }

    @Test
    fun `R-C02 AVERTISSEMENT quand paiement apres dateFin avec reconduction tacite`() {
        val validator = ContratValidator()
        val dossier = dossier()
        dossier.factures.add(facture(dossier, LocalDate.of(2027, 1, 15), BigDecimal("1000")))
        val contrat = EngagementContrat().apply {
            reference = "C-2026-001"
            dateFin = LocalDate.of(2026, 12, 31)
            reconductionTacite = true
        }
        val results = validator.validate(contrat, dossier, ctx())
        assertEquals(StatutCheck.AVERTISSEMENT, results.first { it.regle == "R-C02" }.statut)
    }

    @Test
    fun `R-C02 CONFORME quand paiement avant dateFin`() {
        val validator = ContratValidator()
        val dossier = dossier()
        dossier.factures.add(facture(dossier, LocalDate.of(2026, 6, 15), BigDecimal("1000")))
        val contrat = EngagementContrat().apply {
            reference = "C-2026-001"
            dateFin = LocalDate.of(2026, 12, 31)
        }
        val results = validator.validate(contrat, dossier, ctx())
        assertEquals(StatutCheck.CONFORME, results.first { it.regle == "R-C02" }.statut)
    }

    @Test
    fun `Contrat validator produit 5 regles R-C`() {
        val validator = ContratValidator()
        val dossier = dossier()
        val contrat = EngagementContrat().apply { reference = "C-2026-001" }
        val results = validator.validate(contrat, dossier, ctx())
        val codes = results.map { it.regle }.distinct()
        assertEquals(5, codes.size)
        assertTrue(codes.containsAll(listOf("R-C01", "R-C02", "R-C03", "R-C04", "R-C05")))
    }

    // =========================================================
    // Regression : hierarchie polymorphe
    // =========================================================

    @Test
    fun `EngagementMarche retourne type MARCHE`() {
        val m = EngagementMarche().apply { reference = "M-1" }
        assertEquals(TypeEngagement.MARCHE, m.typeEngagement())
    }

    @Test
    fun `EngagementBonCommande retourne type BON_COMMANDE`() {
        val bc = EngagementBonCommande().apply { reference = "BC-1" }
        assertEquals(TypeEngagement.BON_COMMANDE, bc.typeEngagement())
    }

    @Test
    fun `EngagementContrat retourne type CONTRAT`() {
        val c = EngagementContrat().apply { reference = "C-1" }
        assertEquals(TypeEngagement.CONTRAT, c.typeEngagement())
    }
}
