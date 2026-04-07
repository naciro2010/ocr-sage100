package com.ocrsage.repository

import com.ocrsage.entity.Invoice
import com.ocrsage.entity.InvoiceStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import jakarta.persistence.QueryHint
import java.math.BigDecimal

interface InvoiceRepository : JpaRepository<Invoice, Long> {

    fun findByStatus(status: InvoiceStatus, pageable: Pageable): Page<Invoice>

    fun countByStatus(status: InvoiceStatus): Long

    fun countBySageSynced(synced: Boolean): Long

    /** Fetch invoices with lineItems using a two-query approach (avoids in-memory pagination). */
    @Query("SELECT i FROM Invoice i")
    fun findAllPaged(pageable: Pageable): Page<Invoice>

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.lineItems WHERE i IN :invoices")
    fun fetchWithLineItems(invoices: List<Invoice>): List<Invoice>

    /** Single query: count total + count per status + synced count + processed amount. */
    @Query(
        value = """
            SELECT
                COUNT(*) AS total,
                SUM(CASE WHEN status = 'UPLOADED' THEN 1 ELSE 0 END) AS uploaded,
                SUM(CASE WHEN status = 'OCR_IN_PROGRESS' THEN 1 ELSE 0 END) AS ocr_in_progress,
                SUM(CASE WHEN status = 'OCR_COMPLETED' THEN 1 ELSE 0 END) AS ocr_completed,
                SUM(CASE WHEN status = 'AI_EXTRACTION_IN_PROGRESS' THEN 1 ELSE 0 END) AS ai_extraction,
                SUM(CASE WHEN status = 'EXTRACTED' THEN 1 ELSE 0 END) AS extracted,
                SUM(CASE WHEN status = 'VALIDATION_FAILED' THEN 1 ELSE 0 END) AS validation_failed,
                SUM(CASE WHEN status = 'READY_FOR_SAGE' THEN 1 ELSE 0 END) AS ready_for_sage,
                SUM(CASE WHEN status = 'SAGE_SYNCED' THEN 1 ELSE 0 END) AS sage_synced,
                SUM(CASE WHEN status = 'SAGE_SYNC_FAILED' THEN 1 ELSE 0 END) AS sage_sync_failed,
                SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END) AS error_count,
                SUM(CASE WHEN sage_synced = true THEN 1 ELSE 0 END) AS total_synced,
                COALESCE(SUM(CASE WHEN status IN ('EXTRACTED','READY_FOR_SAGE','SAGE_SYNCED') THEN amount_ttc ELSE 0 END), 0) AS processed_amount
            FROM invoices
        """,
        nativeQuery = true
    )
    @QueryHints(QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    fun getDashboardCounts(): List<Array<Any>>

    @Query(
        value = "SELECT supplier_name, COUNT(*) AS invoice_count FROM invoices WHERE supplier_name IS NOT NULL GROUP BY supplier_name ORDER BY invoice_count DESC LIMIT 10",
        nativeQuery = true
    )
    @QueryHints(QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    fun countBySupplier(): List<Array<Any>>
}
