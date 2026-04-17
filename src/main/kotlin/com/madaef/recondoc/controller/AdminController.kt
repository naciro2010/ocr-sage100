package com.madaef.recondoc.controller

import com.madaef.recondoc.repository.ClaudeUsageRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Read-only ops endpoints. Currently surfaces Claude API consumption
 * (per dossier + daily roll-up) so finance can attribute spend.
 */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val claudeUsageRepository: ClaudeUsageRepository
) {

    @GetMapping("/claude-usage/dossier/{dossierId}")
    fun usageForDossier(@PathVariable dossierId: UUID): Map<String, Any> {
        val raw = claudeUsageRepository.aggregateByDossier(dossierId)
        val row = if (raw.isNotEmpty() && raw[0] is Array<*>) raw[0] as Array<*> else raw
        return mapOf(
            "dossierId" to dossierId,
            "inputTokens" to (row[0] as? Number ?: 0).toLong(),
            "outputTokens" to (row[1] as? Number ?: 0).toLong(),
            "calls" to (row[2] as? Number ?: 0).toLong(),
            "avgDurationMs" to (row[3] as? Number ?: 0).toDouble()
        )
    }

    @GetMapping("/claude-usage/summary")
    fun summary(@RequestParam(defaultValue = "30") days: Long): Map<String, Any> {
        val since = LocalDateTime.now().minusDays(days)
        val raw = claudeUsageRepository.summarySince(since)
        val s = if (raw.isNotEmpty() && raw[0] is Array<*>) raw[0] as Array<*> else raw
        return mapOf(
            "days" to days,
            "since" to since.toLocalDate(),
            "inputTokens" to (s[0] as? Number ?: 0).toLong(),
            "outputTokens" to (s[1] as? Number ?: 0).toLong(),
            "calls" to (s[2] as? Number ?: 0).toLong(),
            "errors" to (s[3] as? Number ?: 0).toLong()
        )
    }

    @GetMapping("/claude-usage/daily")
    fun dailyUsage(@RequestParam(defaultValue = "30") days: Long): List<Map<String, Any>> {
        val since = LocalDateTime.now().minusDays(days)
        return claudeUsageRepository.dailyUsageSince(since).map { row ->
            mapOf(
                "day" to toLocalDate(row[0]),
                "inputTokens" to (row[1] as Number).toLong(),
                "outputTokens" to (row[2] as Number).toLong(),
                "calls" to (row[3] as Number).toLong(),
                "errors" to (row[4] as Number).toLong()
            )
        }
    }

    @GetMapping("/claude-usage/top-dossiers")
    fun topDossiers(
        @RequestParam(defaultValue = "30") days: Long,
        @RequestParam(defaultValue = "10") limit: Int
    ): List<Map<String, Any?>> {
        val since = LocalDateTime.now().minusDays(days)
        return claudeUsageRepository.topDossiersSince(since).take(limit.coerceIn(1, 100)).map { row ->
            mapOf(
                "dossierId" to row[0],
                "reference" to row[1],
                "fournisseur" to row[2],
                "inputTokens" to (row[3] as Number).toLong(),
                "outputTokens" to (row[4] as Number).toLong(),
                "calls" to (row[5] as Number).toLong()
            )
        }
    }

    @GetMapping("/claude-usage/by-model")
    fun byModel(@RequestParam(defaultValue = "30") days: Long): List<Map<String, Any?>> {
        val since = LocalDateTime.now().minusDays(days)
        return claudeUsageRepository.byModelSince(since).map { row ->
            mapOf(
                "model" to row[0],
                "inputTokens" to (row[1] as Number).toLong(),
                "outputTokens" to (row[2] as Number).toLong(),
                "calls" to (row[3] as Number).toLong()
            )
        }
    }

    private fun toLocalDate(raw: Any?): java.time.LocalDate = when (raw) {
        is java.sql.Date -> raw.toLocalDate()
        is java.time.LocalDate -> raw
        is java.time.LocalDateTime -> raw.toLocalDate()
        else -> LocalDate.parse(raw.toString())
    }
}
