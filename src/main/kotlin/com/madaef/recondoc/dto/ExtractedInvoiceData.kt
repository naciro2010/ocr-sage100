package com.madaef.recondoc.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDate

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractedInvoiceData(
    // Supplier
    @JsonProperty("supplier_name") val supplierName: String? = null,
    @JsonProperty("supplier_ice") val supplierIce: String? = null,
    @JsonProperty("supplier_if") val supplierIf: String? = null,
    @JsonProperty("supplier_rc") val supplierRc: String? = null,
    @JsonProperty("supplier_patente") val supplierPatente: String? = null,
    @JsonProperty("supplier_cnss") val supplierCnss: String? = null,
    @JsonProperty("supplier_address") val supplierAddress: String? = null,
    @JsonProperty("supplier_city") val supplierCity: String? = null,

    // Client
    @JsonProperty("client_name") val clientName: String? = null,
    @JsonProperty("client_ice") val clientIce: String? = null,

    // Invoice
    @JsonProperty("invoice_number") val invoiceNumber: String? = null,
    @JsonProperty("invoice_date") val invoiceDate: LocalDate? = null,

    // Amounts
    @JsonProperty("amount_ht") val amountHt: BigDecimal? = null,
    @JsonProperty("tva_rate") val tvaRate: BigDecimal? = null,
    @JsonProperty("amount_tva") val amountTva: BigDecimal? = null,
    @JsonProperty("amount_ttc") val amountTtc: BigDecimal? = null,
    @JsonProperty("discount_amount") val discountAmount: BigDecimal? = null,
    @JsonProperty("discount_percent") val discountPercent: BigDecimal? = null,
    @JsonProperty("currency") val currency: String? = null,

    // Payment
    @JsonProperty("payment_method") val paymentMethod: String? = null,
    @JsonProperty("payment_due_date") val paymentDueDate: LocalDate? = null,
    @JsonProperty("bank_name") val bankName: String? = null,
    @JsonProperty("bank_rib") val bankRib: String? = null,

    // Line items
    @JsonProperty("line_items") val lineItems: List<ExtractedLineItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractedLineItem(
    @JsonProperty("line_number") val lineNumber: Int = 0,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("quantity") val quantity: BigDecimal? = null,
    @JsonProperty("unit") val unit: String? = null,
    @JsonProperty("unit_price_ht") val unitPriceHt: BigDecimal? = null,
    @JsonProperty("tva_rate") val tvaRate: BigDecimal? = null,
    @JsonProperty("tva_amount") val tvaAmount: BigDecimal? = null,
    @JsonProperty("total_ht") val totalHt: BigDecimal? = null,
    @JsonProperty("total_ttc") val totalTtc: BigDecimal? = null
)
