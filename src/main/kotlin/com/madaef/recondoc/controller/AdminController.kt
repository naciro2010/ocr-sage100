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
        val row = claudeUsageRepository.aggregateByDossier(dossierId)
        return mapOf(
            "dossierId" to dossierId,
            "inputTokens" to (row[0] as Number).toLong(),
            "outputTokens" to (row[1] as Number).toLong(),
            "calls" to (row[2] as Number).toLong(),
            "avgDurationMs" to (row[3] as Number).toDouble()
        )
    }

    @GetMapping("/claude-usage/daily")
    fun dailyUsage(@RequestParam(defaultValue = "30") days: Long): List<Map<String, Any>> {
        val since = LocalDateTime.now().minusDays(days)
        return claudeUsageRepository.dailyUsageSince(since).map { row ->
            mapOf(
                "day" to (row[0] as java.sql.Date).toLocalDate(),
                "inputTokens" to (row[1] as Number).toLong(),
                "outputTokens" to (row[2] as Number).toLong(),
                "calls" to (row[3] as Number).toLong(),
                "errors" to (row[4] as Number).toLong()
            )
        }
    }
}
