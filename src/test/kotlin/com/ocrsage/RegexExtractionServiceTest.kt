package com.ocrsage

import com.ocrsage.service.RegexExtractionService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for RegexExtractionService using a realistic Moroccan invoice.
 */
class RegexExtractionServiceTest {

    private lateinit var service: RegexExtractionService

    private val sampleInvoiceText = """
        SOCIETE ATLAS DISTRIBUTION SARL
        Zone Industrielle, Lot 45, Ain Sebaa
        Casablanca, Maroc
        Tel: 05 22 35 67 89

        ICE: 001234567000078
        I.F.: 12345678
        R.C.: 123456
        Patente: 45678901
        CNSS: 7890123

        FACTURE

        Facture N°: FA-2024/0157
        Date Facture: 15/03/2024

        Client: ENTREPRISE MAGHREB SERVICES SA
        ICE Client: 009876543000012

        Désignation          | Qté | Unité | P.U. HT  | Taux TVA | Total HT
        Papier A4 80g        |  50 | Rame  |   45,00  |   20%    | 2 250,00
        Cartouche Toner HP   |  10 | Pcs   |  350,00  |   20%    | 3 500,00
        Classeurs A4         | 100 | Pcs   |   12,50  |   20%    | 1 250,00

        Montant HT:     7 000,00 MAD
        TVA 20%:        1 400,00 MAD
        Montant TTC:    8 400,00 MAD

        Mode de paiement: Virement
        Banque: Attijariwafa Bank
        RIB: 007780001234567890120097

        Date d'echeance: 15/04/2024
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        service = RegexExtractionService()
    }

    @Test
    fun `extract ICE from invoice`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("001234567000078", result.supplierIce)
    }

    @Test
    fun `extract IF from invoice`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("12345678", result.supplierIf)
    }

    @Test
    fun `extract RC from invoice`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("123456", result.supplierRc)
    }

    @Test
    fun `extract Patente from invoice`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("45678901", result.supplierPatente)
    }

    @Test
    fun `extract CNSS from invoice`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("7890123", result.supplierCnss)
    }

    @Test
    fun `extract invoice number`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("FA-2024/0157", result.invoiceNumber)
    }

    @Test
    fun `extract invoice date`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals(LocalDate.of(2024, 3, 15), result.invoiceDate)
    }

    @Test
    fun `extract amounts HT, TVA, TTC`() {
        val result = service.extract(sampleInvoiceText)
        assertNotNull(result.amountHt, "Amount HT should be extracted")
        assertNotNull(result.amountTva, "Amount TVA should be extracted")
        assertNotNull(result.amountTtc, "Amount TTC should be extracted")

        assertEquals(0, BigDecimal("7000.00").compareTo(result.amountHt), "HT should be 7000.00")
        assertEquals(0, BigDecimal("1400.00").compareTo(result.amountTva), "TVA should be 1400.00")
        assertEquals(0, BigDecimal("8400.00").compareTo(result.amountTtc), "TTC should be 8400.00")
    }

    @Test
    fun `extract TVA rate`() {
        val result = service.extract(sampleInvoiceText)
        assertNotNull(result.tvaRate)
        assertEquals(0, BigDecimal("20").compareTo(result.tvaRate))
    }

    @Test
    fun `extract payment method`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("Virement", result.paymentMethod)
    }

    @Test
    fun `extract bank name`() {
        val result = service.extract(sampleInvoiceText)
        assertNotNull(result.bankName)
        assertTrue(result.bankName!!.contains("Attijariwafa"), "Should detect Attijariwafa bank")
    }

    @Test
    fun `extract RIB`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("007780001234567890120097", result.bankRib)
    }

    @Test
    fun `extract currency`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("MAD", result.currency)
    }

    @Test
    fun `extract client name`() {
        val result = service.extract(sampleInvoiceText)
        assertNotNull(result.clientName)
        assertTrue(result.clientName!!.contains("MAGHREB"), "Should detect client name")
    }

    @Test
    fun `extract supplier city - Casablanca`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals("Casablanca", result.supplierCity)
    }

    @Test
    fun `extract due date`() {
        val result = service.extract(sampleInvoiceText)
        assertEquals(LocalDate.of(2024, 4, 15), result.paymentDueDate)
    }

    @Test
    fun `extract from empty text returns all nulls`() {
        val result = service.extract("")
        assertNull(result.supplierIce)
        assertNull(result.invoiceNumber)
        assertNull(result.amountHt)
        assertNull(result.amountTtc)
    }

    @Test
    fun `extract from minimal invoice`() {
        val minimal = """
            ICE: 000000000000000
            Facture N°: F001
            Montant TTC: 100,00
        """.trimIndent()

        val result = service.extract(minimal)
        assertEquals("000000000000000", result.supplierIce)
        assertEquals("F001", result.invoiceNumber)
        assertNotNull(result.amountTtc)
    }
}
