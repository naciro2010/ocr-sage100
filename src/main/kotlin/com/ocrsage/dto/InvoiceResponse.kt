package com.ocrsage.dto

import com.ocrsage.entity.Invoice
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class InvoiceResponse(
    val id: Long?,
    val fileName: String,
    val status: String,
    val supplierName: String?,
    val invoiceNumber: String?,
    val invoiceDate: LocalDate?,
    val amountHt: BigDecimal?,
    val amountTva: BigDecimal?,
    val amountTtc: BigDecimal?,
    val currency: String,
    val sageSynced: Boolean,
    val sageReference: String?,
    val errorMessage: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(invoice: Invoice) = InvoiceResponse(
            id = invoice.id,
            fileName = invoice.fileName,
            status = invoice.status.name,
            supplierName = invoice.supplierName,
            invoiceNumber = invoice.invoiceNumber,
            invoiceDate = invoice.invoiceDate,
            amountHt = invoice.amountHt,
            amountTva = invoice.amountTva,
            amountTtc = invoice.amountTtc,
            currency = invoice.currency,
            sageSynced = invoice.sageSynced,
            sageReference = invoice.sageReference,
            errorMessage = invoice.errorMessage,
            createdAt = invoice.createdAt,
            updatedAt = invoice.updatedAt
        )
    }
}
