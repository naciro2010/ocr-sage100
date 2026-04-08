package com.madaef.recondoc

import com.madaef.recondoc.entity.Invoice
import com.madaef.recondoc.entity.InvoiceLineItem
import com.madaef.recondoc.service.ValidationService
import com.madaef.recondoc.service.ValidationSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for ValidationService - Moroccan invoice validation rules.
 */
class ValidationServiceTest {

    private lateinit var service: ValidationService

    @BeforeEach
    fun setUp() {
        service = ValidationService()
    }

    private fun validInvoice() = Invoice(
        fileName = "test.pdf",
        filePath = "/tmp/test.pdf"
    ).apply {
        supplierName = "Test Supplier"
        supplierIce = "001234567000078"
        supplierIf = "12345678"
        invoiceNumber = "FA-001"
        invoiceDate = LocalDate.of(2024, 3, 15)
        amountHt = BigDecimal("7000.00")
        tvaRate = BigDecimal("20")
        amountTva = BigDecimal("1400.00")
        amountTtc = BigDecimal("8400.00")
        currency = "MAD"
    }

    @Test
    fun `valid Moroccan invoice passes validation`() {
        val invoice = validInvoice()
        val result = service.validateInvoice(invoice)
        assertTrue(result.valid, "Valid invoice should pass: errors=${result.errors}")
    }

    @Test
    fun `invalid ICE format is flagged as error`() {
        val invoice = validInvoice().apply { supplierIce = "12345" }
        val result = service.validateInvoice(invoice)
        assertTrue(result.errors.any { it.field.contains("ICE") }, "Should flag invalid ICE")
    }

    @Test
    fun `missing ICE is flagged as warning`() {
        val invoice = validInvoice().apply { supplierIce = null }
        val result = service.validateInvoice(invoice)
        assertTrue(result.warnings.any { it.field.contains("ICE") }, "Should warn about missing ICE")
    }

    @Test
    fun `invalid IF format is flagged as error`() {
        val invoice = validInvoice().apply { supplierIf = "123" }
        val result = service.validateInvoice(invoice)
        assertTrue(result.errors.any { it.field.contains("IF") }, "Should flag invalid IF")
    }

    @Test
    fun `invalid TVA rate is flagged as error`() {
        val invoice = validInvoice().apply { tvaRate = BigDecimal("15") }
        val result = service.validateInvoice(invoice)
        assertTrue(result.errors.any { it.field.contains("TVA") }, "Should flag invalid TVA rate 15%")
    }

    @Test
    fun `all legal TVA rates are accepted`() {
        val legalRates = listOf("0", "7", "10", "14", "20")
        for (rate in legalRates) {
            val invoice = validInvoice().apply {
                tvaRate = BigDecimal(rate)
                amountTva = amountHt!!.multiply(BigDecimal(rate)).divide(BigDecimal(100))
                amountTtc = amountHt!!.add(amountTva!!)
            }
            val result = service.validateInvoice(invoice)
            assertFalse(
                result.errors.any { it.field.contains("Taux TVA") },
                "TVA rate $rate% should be accepted"
            )
        }
    }

    @Test
    fun `amount inconsistency HT plus TVA not equal TTC is flagged`() {
        val invoice = validInvoice().apply {
            amountHt = BigDecimal("7000.00")
            amountTva = BigDecimal("1400.00")
            amountTtc = BigDecimal("9000.00") // Wrong!
        }
        val result = service.validateInvoice(invoice)
        assertTrue(result.errors.any { it.field == "Montants" }, "Should flag amount inconsistency")
    }

    @Test
    fun `valid RIB with 24 digits passes`() {
        val invoice = validInvoice().apply {
            bankRib = "007780001234567890120097"
        }
        val result = service.validateInvoice(invoice)
        // Should not have an ERROR about RIB format (might have WARNING about check digit)
        assertFalse(
            result.errors.any { it.field == "RIB" && it.message.contains("24 chiffres") },
            "Valid 24-digit RIB should not fail format check"
        )
    }

    @Test
    fun `invalid RIB length is flagged as error`() {
        val invoice = validInvoice().apply { bankRib = "12345" }
        val result = service.validateInvoice(invoice)
        assertTrue(result.errors.any { it.field == "RIB" }, "Should flag invalid RIB length")
    }

    @Test
    fun `unsupported currency is flagged as error`() {
        val invoice = validInvoice().apply { currency = "GBP" }
        val result = service.validateInvoice(invoice)
        assertTrue(result.errors.any { it.field == "Devise" }, "Should flag unsupported currency")
    }

    @Test
    fun `MAD EUR USD currencies are accepted`() {
        for (cur in listOf("MAD", "EUR", "USD")) {
            val invoice = validInvoice().apply { currency = cur }
            val result = service.validateInvoice(invoice)
            assertFalse(
                result.errors.any { it.field == "Devise" },
                "Currency $cur should be accepted"
            )
        }
    }

    @Test
    fun `missing required fields are flagged`() {
        val invoice = validInvoice().apply {
            supplierName = null
            invoiceNumber = null
            amountTtc = null
        }
        val result = service.validateInvoice(invoice)
        assertTrue(result.errors.any { it.field == "Fournisseur" })
        assertTrue(result.errors.any { it.field.contains("Facture") })
        assertTrue(result.errors.any { it.field.contains("TTC") })
    }

    @Test
    fun `line items consistency check`() {
        val invoice = validInvoice().apply {
            lineItems.add(InvoiceLineItem(
                invoice = this,
                lineNumber = 1,
                description = "Item A",
                quantity = BigDecimal("10"),
                unitPriceHt = BigDecimal("100"),
                totalHt = BigDecimal("1000.00"),
                tvaRate = BigDecimal("20")
            ))
            lineItems.add(InvoiceLineItem(
                invoice = this,
                lineNumber = 2,
                description = "Item B",
                quantity = BigDecimal("20"),
                unitPriceHt = BigDecimal("300"),
                totalHt = BigDecimal("6000.00"),
                tvaRate = BigDecimal("20")
            ))
            // Sum = 7000, matches amountHt
        }
        val result = service.validateInvoice(invoice)
        assertFalse(
            result.warnings.any { it.field == "Lignes" && it.message.contains("ne correspond pas") },
            "Line items sum matches HT, should not warn"
        )
    }

    @Test
    fun `line items with illegal TVA rate are flagged`() {
        val invoice = validInvoice().apply {
            lineItems.add(InvoiceLineItem(
                invoice = this,
                lineNumber = 1,
                description = "Item",
                tvaRate = BigDecimal("15") // Invalid for Morocco
            ))
        }
        val result = service.validateInvoice(invoice)
        assertTrue(
            result.errors.any { it.field.contains("Ligne 1") && it.field.contains("TVA") },
            "Should flag invalid TVA rate on line item"
        )
    }
}
