package com.ocrsage.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "invoices")
class Invoice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "file_name", nullable = false)
    var fileName: String,

    @Column(name = "file_path", nullable = false)
    var filePath: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: InvoiceStatus = InvoiceStatus.UPLOADED,

    @Column(name = "raw_text", columnDefinition = "TEXT")
    var rawText: String? = null,

    @Column(name = "supplier_name")
    var supplierName: String? = null,

    @Column(name = "invoice_number")
    var invoiceNumber: String? = null,

    @Column(name = "invoice_date")
    var invoiceDate: LocalDate? = null,

    @Column(name = "amount_ht", precision = 15, scale = 2)
    var amountHt: BigDecimal? = null,

    @Column(name = "amount_tva", precision = 15, scale = 2)
    var amountTva: BigDecimal? = null,

    @Column(name = "amount_ttc", precision = 15, scale = 2)
    var amountTtc: BigDecimal? = null,

    @Column(length = 10)
    var currency: String = "MAD",

    @Column(name = "sage_synced", nullable = false)
    var sageSynced: Boolean = false,

    @Column(name = "sage_sync_date")
    var sageSyncDate: LocalDateTime? = null,

    @Column(name = "sage_reference")
    var sageReference: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }
}

enum class InvoiceStatus {
    UPLOADED,
    OCR_IN_PROGRESS,
    OCR_COMPLETED,
    AI_EXTRACTION_IN_PROGRESS,
    EXTRACTED,
    VALIDATION_FAILED,
    READY_FOR_SAGE,
    SAGE_SYNCED,
    SAGE_SYNC_FAILED,
    ERROR
}
