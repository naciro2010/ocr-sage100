package com.madaef.recondoc.repository

import com.madaef.recondoc.entity.ClaudeUsage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime
import java.util.UUID

interface ClaudeUsageRepository : JpaRepository<ClaudeUsage, UUID> {

    @Query("""
        SELECT COALESCE(SUM(u.inputTokens), 0), COALESCE(SUM(u.outputTokens), 0), COUNT(u), COALESCE(AVG(u.durationMs), 0)
        FROM ClaudeUsage u
        WHERE u.dossierId = :dossierId
    """)
    fun aggregateByDossier(dossierId: UUID): Array<Any>

    @Query("""
        SELECT COALESCE(SUM(u.inputTokens), 0),
               COALESCE(SUM(u.outputTokens), 0),
               COUNT(u),
               COUNT(CASE WHEN u.success = false THEN 1 END)
        FROM ClaudeUsage u
        WHERE u.createdAt >= :since
    """)
    fun summarySince(since: LocalDateTime): Array<Any>

    @Query("""
        SELECT DATE(u.createdAt) AS day,
               COALESCE(SUM(u.inputTokens), 0) AS inTokens,
               COALESCE(SUM(u.outputTokens), 0) AS outTokens,
               COUNT(u) AS calls,
               COUNT(CASE WHEN u.success = false THEN 1 END) AS errors
        FROM ClaudeUsage u
        WHERE u.createdAt >= :since
        GROUP BY DATE(u.createdAt)
        ORDER BY day DESC
    """)
    fun dailyUsageSince(since: LocalDateTime): List<Array<Any>>

    @Query("""
        SELECT u.dossierId AS dossierId,
               d.reference AS reference,
               d.fournisseur AS fournisseur,
               COALESCE(SUM(u.inputTokens), 0) AS inTokens,
               COALESCE(SUM(u.outputTokens), 0) AS outTokens,
               COUNT(u) AS calls
        FROM ClaudeUsage u
        LEFT JOIN com.madaef.recondoc.entity.dossier.DossierPaiement d ON d.id = u.dossierId
        WHERE u.createdAt >= :since AND u.dossierId IS NOT NULL
        GROUP BY u.dossierId, d.reference, d.fournisseur
        ORDER BY SUM(u.inputTokens + u.outputTokens) DESC
    """)
    fun topDossiersSince(since: LocalDateTime): List<Array<Any?>>

    @Query("""
        SELECT u.model AS model,
               COALESCE(SUM(u.inputTokens), 0) AS inTokens,
               COALESCE(SUM(u.outputTokens), 0) AS outTokens,
               COUNT(u) AS calls
        FROM ClaudeUsage u
        WHERE u.createdAt >= :since
        GROUP BY u.model
        ORDER BY SUM(u.inputTokens + u.outputTokens) DESC
    """)
    fun byModelSince(since: LocalDateTime): List<Array<Any?>>
}
