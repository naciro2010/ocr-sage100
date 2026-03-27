package com.ocrsage.repository

import com.ocrsage.entity.Invoice
import com.ocrsage.entity.InvoiceStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal

interface InvoiceRepository : JpaRepository<Invoice, Long> {

    fun findByStatus(status: InvoiceStatus, pageable: Pageable): Page<Invoice>

    fun countByStatus(status: InvoiceStatus): Long

    fun countBySageSynced(synced: Boolean): Long

    @Query("SELECT COALESCE(SUM(i.amountTtc), 0) FROM Invoice i WHERE i.status = 'EXTRACTED' OR i.status = 'READY_FOR_SAGE' OR i.status = 'SAGE_SYNCED'")
    fun sumProcessedAmounts(): BigDecimal

    @Query("SELECT i.supplierName, COUNT(i) FROM Invoice i WHERE i.supplierName IS NOT NULL GROUP BY i.supplierName ORDER BY COUNT(i) DESC")
    fun countBySupplier(pageable: Pageable): List<Array<Any>>
}
