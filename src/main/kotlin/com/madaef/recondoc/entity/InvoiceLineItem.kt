package com.madaef.recondoc.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "invoice_line_items")
class InvoiceLineItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    var invoice: Invoice? = null,

    @Column(name = "line_number", nullable = false)
    var lineNumber: Int,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(precision = 15, scale = 3)
    var quantity: BigDecimal? = null,

    @Column(length = 50)
    var unit: String? = null,

    @Column(name = "unit_price_ht", precision = 15, scale = 2)
    var unitPriceHt: BigDecimal? = null,

    @Column(name = "tva_rate", precision = 5, scale = 2)
    var tvaRate: BigDecimal? = null,

    @Column(name = "tva_amount", precision = 15, scale = 2)
    var tvaAmount: BigDecimal? = null,

    @Column(name = "total_ht", precision = 15, scale = 2)
    var totalHt: BigDecimal? = null,

    @Column(name = "total_ttc", precision = 15, scale = 2)
    var totalTtc: BigDecimal? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)
