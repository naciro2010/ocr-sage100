package com.ocrsage.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDate

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractedInvoiceData(
    @JsonProperty("supplier_name") val supplierName: String? = null,
    @JsonProperty("invoice_number") val invoiceNumber: String? = null,
    @JsonProperty("invoice_date") val invoiceDate: LocalDate? = null,
    @JsonProperty("amount_ht") val amountHt: BigDecimal? = null,
    @JsonProperty("amount_tva") val amountTva: BigDecimal? = null,
    @JsonProperty("amount_ttc") val amountTtc: BigDecimal? = null,
    @JsonProperty("currency") val currency: String? = null
)
