package com.madaef.recondoc.controller

import com.madaef.recondoc.repository.ClaudeUsageRepository
import com.madaef.recondoc.repository.dossier.ResultatValidationRepository
import com.madaef.recondoc.service.AppSettingsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
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
    private val claudeUsageRepository: ClaudeUsageRepository,
    private val appSettings: AppSettingsService,
    private val resultatRepository: ResultatValidationRepository
) {

    @GetMapping("/rules/performance")
    fun rulesPerformance(): List<Map<String, Any?>> {
        return resultatRepository.aggregateDurationByRule().map { row ->
            mapOf(
                "regle" to row[0],
                "count" to (row[1] as? Number)?.toLong(),
                "avgDurationMs" to (row[2] as? Number)?.toDouble(),
                "maxDurationMs" to (row[3] as? Number)?.toLong(),
                "minDurationMs" to (row[4] as? Number)?.toLong()
            )
        }
    }

    /**
     * Taux de correction manuelle par regle sur la fenetre glissante.
     * Permet d'identifier les regles qui crient a tort (faux positifs :
     * le verdict auto NON_CONFORME est corrige manuellement en CONFORME)
     * ou qui laissent passer (faux negatifs : CONFORME -> NON_CONFORME).
     * Objectif : guider le retuning des regles deterministes et l'ecriture
     * de regles CUSTOM pour les cas mal detectes.
     */
    @GetMapping("/rules/corrections")
    fun rulesCorrections(@RequestParam(defaultValue = "90") days: Long): List<Map<String, Any?>> {
        val since = LocalDateTime.now().minusDays(days)
        return resultatRepository.aggregateCorrectionsByRule(since).map { row ->
            val total = (row[1] as? Number)?.toLong() ?: 0L
            val corrections = (row[2] as? Number)?.toLong() ?: 0L
            val falsePositives = (row[3] as? Number)?.toLong() ?: 0L
            val falseNegatives = (row[4] as? Number)?.toLong() ?: 0L
            mapOf(
                "regle" to row[0],
                "total" to total,
                "corrections" to corrections,
                "falsePositives" to falsePositives,
                "falseNegatives" to falseNegatives,
                "correctionRate" to if (total > 0) corrections.toDouble() / total else 0.0
            )
        }
    }

    @GetMapping("/claude-usage/dossier/{dossierId}")
    fun usageForDossier(@PathVariable dossierId: UUID): Map<String, Any> {
        val raw = claudeUsageRepository.aggregateByDossier(dossierId)
        val row = if (raw.isNotEmpty() && raw[0] is Array<*>) raw[0] as Array<*> else raw
        val inTok = (row[0] as? Number ?: 0).toLong()
        val outTok = (row[1] as? Number ?: 0).toLong()
        val calls = (row[2] as? Number ?: 0).toLong()
        return mapOf(
            "dossierId" to dossierId,
            "inputTokens" to inTok,
            "outputTokens" to outTok,
            "calls" to calls,
            "avgDurationMs" to (row[3] as? Number ?: 0).toDouble(),
            "estimatedCostUsd" to appSettings.estimateCostUsd(appSettings.getAiModel(), inTok, outTok)
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
            "errors" to (s[3] as? Number ?: 0).toLong(),
            "cacheCreationInputTokens" to (s[4] as? Number ?: 0).toLong(),
            "cacheReadInputTokens" to (s[5] as? Number ?: 0).toLong()
        )
    }

    @GetMapping("/claude-usage/pricing")
    fun pricing(): Map<String, Map<String, Double>> {
        return appSettings.getAllPricing().mapValues { (_, p) ->
            mapOf("input" to p.first, "output" to p.second)
        }
    }

    data class PricingPayload(val model: String, val input: Double, val output: Double)

    @PutMapping("/claude-usage/pricing")
    fun updatePricing(@RequestBody payload: PricingPayload): Map<String, Any> {
        require(payload.model.isNotBlank()) { "model required" }
        require(payload.input >= 0 && payload.output >= 0) { "prices must be positive" }
        appSettings.set("ai.price.${payload.model}.in", payload.input.toString())
        appSettings.set("ai.price.${payload.model}.out", payload.output.toString())
        return mapOf("model" to payload.model, "input" to payload.input, "output" to payload.output)
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
