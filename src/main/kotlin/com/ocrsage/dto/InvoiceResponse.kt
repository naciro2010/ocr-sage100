package com.ocrsage.dto

import com.ocrsage.entity.Invoice
import com.ocrsage.entity.InvoiceLineItem
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class InvoiceResponse(
    val id: Long?,
    val fileName: String,
    val status: String,
    val rawText: String?,

    // Supplier
    val supplierName: String?,
    val supplierIce: String?,
    val supplierIf: String?,
    val supplierRc: String?,
    val supplierPatente: String?,
    val supplierCnss: String?,
    val supplierAddress: String?,
    val supplierCity: String?,

    // Client
    val clientName: String?,
    val clientIce: String?,

    // Invoice
    val invoiceNumber: String?,
    val invoiceDate: LocalDate?,

    // Amounts
    val amountHt: BigDecimal?,
    val tvaRate: BigDecimal?,
    val amountTva: BigDecimal?,
    val amountTtc: BigDecimal?,
    val discountAmount: BigDecimal?,
    val discountPercent: BigDecimal?,
    val currency: String,

    // Payment
    val paymentMethod: String?,
    val paymentDueDate: LocalDate?,
    val bankName: String?,
    val bankRib: String?,

    // Line items
    val lineItems: List<LineItemResponse>,

    // OCR metadata
    val ocrEngine: String?,
    val ocrConfidence: Double?,
    val ocrPageCount: Int?,
    val aiUsed: Boolean,

    // Sage
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
            rawText = invoice.rawText,
            supplierName = invoice.supplierName,
            supplierIce = invoice.supplierIce,
            supplierIf = invoice.supplierIf,
            supplierRc = invoice.supplierRc,
            supplierPatente = invoice.supplierPatente,
            supplierCnss = invoice.supplierCnss,
            supplierAddress = invoice.supplierAddress,
            supplierCity = invoice.supplierCity,
            clientName = invoice.clientName,
            clientIce = invoice.clientIce,
            invoiceNumber = invoice.invoiceNumber,
            invoiceDate = invoice.invoiceDate,
            amountHt = invoice.amountHt,
            tvaRate = invoice.tvaRate,
            amountTva = invoice.amountTva,
            amountTtc = invoice.amountTtc,
            discountAmount = invoice.discountAmount,
            discountPercent = invoice.discountPercent,
            currency = invoice.currency,
            paymentMethod = invoice.paymentMethod,
            paymentDueDate = invoice.paymentDueDate,
            bankName = invoice.bankName,
            bankRib = invoice.bankRib,
            lineItems = invoice.lineItems.map { LineItemResponse.from(it) },
            ocrEngine = invoice.ocrEngine,
            ocrConfidence = invoice.ocrConfidence,
            ocrPageCount = invoice.ocrPageCount,
            aiUsed = invoice.aiUsed,
            sageSynced = invoice.sageSynced,
            sageReference = invoice.sageReference,
            errorMessage = invoice.errorMessage,
            createdAt = invoice.createdAt,
            updatedAt = invoice.updatedAt
        )
    }
}

data class LineItemResponse(
    val id: Long?,
    val lineNumber: Int,
    val description: String?,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPriceHt: BigDecimal?,
    val tvaRate: BigDecimal?,
    val tvaAmount: BigDecimal?,
    val totalHt: BigDecimal?,
    val totalTtc: BigDecimal?
) {
    companion object {
        fun from(item: InvoiceLineItem) = LineItemResponse(
            id = item.id,
            lineNumber = item.lineNumber,
            description = item.description,
            quantity = item.quantity,
            unit = item.unit,
            unitPriceHt = item.unitPriceHt,
            tvaRate = item.tvaRate,
            tvaAmount = item.tvaAmount,
            totalHt = item.totalHt,
            totalTtc = item.totalTtc
        )
    }
}
