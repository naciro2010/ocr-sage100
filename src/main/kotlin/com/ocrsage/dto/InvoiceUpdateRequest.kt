package com.ocrsage.dto

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Request body for manually updating/correcting invoice data.
 * All fields are optional — only non-null fields are applied.
 */
data class InvoiceUpdateRequest(
    val supplierName: String? = null,
    val supplierIce: String? = null,
    val supplierIf: String? = null,
    val supplierRc: String? = null,
    val supplierPatente: String? = null,
    val supplierCnss: String? = null,
    val supplierAddress: String? = null,
    val supplierCity: String? = null,
    val clientName: String? = null,
    val clientIce: String? = null,
    val invoiceNumber: String? = null,
    val invoiceDate: LocalDate? = null,
    val amountHt: BigDecimal? = null,
    val tvaRate: BigDecimal? = null,
    val amountTva: BigDecimal? = null,
    val amountTtc: BigDecimal? = null,
    val discountAmount: BigDecimal? = null,
    val discountPercent: BigDecimal? = null,
    val currency: String? = null,
    val paymentMethod: String? = null,
    val paymentDueDate: LocalDate? = null,
    val bankName: String? = null,
    val bankRib: String? = null
)
