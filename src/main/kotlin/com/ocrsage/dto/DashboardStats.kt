package com.ocrsage.dto

import java.math.BigDecimal

data class DashboardStats(
    val totalInvoices: Long,
    val byStatus: Map<String, Long>,
    val sageSynced: Long,
    val pendingSync: Long,
    val totalProcessedAmount: BigDecimal,
    val topSuppliers: Map<String, Long>
)
