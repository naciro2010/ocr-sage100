package com.ocrsage.repository

import com.ocrsage.entity.Invoice
import com.ocrsage.entity.InvoiceStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import jakarta.persistence.QueryHint
import java.math.BigDecimal

interface InvoiceRepository : JpaRepository<Invoice, Long> {

    fun findByStatus(status: InvoiceStatus, pageable: Pageable): Page<Invoice>

    fun countByStatus(status: InvoiceStatus): Long

    fun countBySageSynced(synced: Boolean): Long

    @Query("SELECT COALESCE(SUM(i.amountTtc), 0) FROM Invoice i WHERE i.status = com.ocrsage.entity.InvoiceStatus.EXTRACTED OR i.status = com.ocrsage.entity.InvoiceStatus.READY_FOR_SAGE OR i.status = com.ocrsage.entity.InvoiceStatus.SAGE_SYNCED")
    fun sumProcessedAmounts(): BigDecimal

    @Query(
        value = "SELECT supplier_name, COUNT(*) AS invoice_count FROM invoices WHERE supplier_name IS NOT NULL GROUP BY supplier_name ORDER BY invoice_count DESC LIMIT 10",
        nativeQuery = true
    )
    @QueryHints(QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    fun countBySupplier(): List<Array<Any>>
}
