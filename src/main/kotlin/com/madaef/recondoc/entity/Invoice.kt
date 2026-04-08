package com.madaef.recondoc.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(
    name = "invoices",
    indexes = [
        Index(name = "idx_invoices_supplier", columnList = "supplier_name"),
        Index(name = "idx_invoices_status", columnList = "status"),
        Index(name = "idx_invoices_created_at", columnList = "created_at")
    ]
)
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

    // --- Supplier info ---
    @Column(name = "supplier_name")
    var supplierName: String? = null,

    @Column(name = "supplier_ice")
    var supplierIce: String? = null,

    @Column(name = "supplier_if")
    var supplierIf: String? = null,

    @Column(name = "supplier_rc")
    var supplierRc: String? = null,

    @Column(name = "supplier_patente")
    var supplierPatente: String? = null,

    @Column(name = "supplier_cnss")
    var supplierCnss: String? = null,

    @Column(name = "supplier_address", columnDefinition = "TEXT")
    var supplierAddress: String? = null,

    @Column(name = "supplier_city")
    var supplierCity: String? = null,

    // --- Client info ---
    @Column(name = "client_name")
    var clientName: String? = null,

    @Column(name = "client_ice")
    var clientIce: String? = null,

    // --- Invoice details ---
    @Column(name = "invoice_number")
    var invoiceNumber: String? = null,

    @Column(name = "invoice_date")
    var invoiceDate: LocalDate? = null,

    @Column(name = "amount_ht", precision = 15, scale = 2)
    var amountHt: BigDecimal? = null,

    @Column(name = "tva_rate", precision = 5, scale = 2)
    var tvaRate: BigDecimal? = null,

    @Column(name = "amount_tva", precision = 15, scale = 2)
    var amountTva: BigDecimal? = null,

    @Column(name = "amount_ttc", precision = 15, scale = 2)
    var amountTtc: BigDecimal? = null,

    @Column(name = "discount_amount", precision = 15, scale = 2)
    var discountAmount: BigDecimal? = null,

    @Column(name = "discount_percent", precision = 5, scale = 2)
    var discountPercent: BigDecimal? = null,

    @Column(length = 10)
    var currency: String = "MAD",

    // --- Payment ---
    @Column(name = "payment_method")
    var paymentMethod: String? = null,

    @Column(name = "payment_due_date")
    var paymentDueDate: LocalDate? = null,

    @Column(name = "bank_name")
    var bankName: String? = null,

    @Column(name = "bank_rib")
    var bankRib: String? = null,

    // --- Line items ---
    @OneToMany(mappedBy = "invoice", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    var lineItems: MutableList<InvoiceLineItem> = mutableListOf(),

    // --- Sage sync ---
    @Column(name = "sage_synced", nullable = false)
    var sageSynced: Boolean = false,

    @Column(name = "sage_sync_date")
    var sageSyncDate: LocalDateTime? = null,

    @Column(name = "sage_reference")
    var sageReference: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    // --- ERP ---
    @Column(name = "erp_type")
    var erpType: String? = "SAGE_1000",

    // --- OCR metadata ---
    @Column(name = "ocr_engine")
    var ocrEngine: String? = null,

    @Column(name = "ocr_confidence")
    var ocrConfidence: Double? = null,

    @Column(name = "ocr_page_count")
    var ocrPageCount: Int? = null,

    @Column(name = "ai_used")
    var aiUsed: Boolean = false,

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
