package com.madaef.recondoc.controller

import com.madaef.recondoc.service.AppSettingsService
import com.madaef.recondoc.service.MistralOcrClient
import com.madaef.recondoc.service.storage.DocumentStorage
import com.madaef.recondoc.service.storage.S3DocumentStorage
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.system.measureTimeMillis

/**
 * Point unique d'observabilite applicative, consomme par la page "Etat systeme"
 * du front. Agrege l'etat reel (pas que des flags de config) en executant des
 * probes legers : SELECT 1 sur la DB, verif taille du repertoire uploads, etat
 * du circuit breaker Claude, configuration Mistral OCR.
 */
@RestController
@RequestMapping("/api/admin/system")
class SystemHealthController(
    private val jdbc: JdbcTemplate,
    private val appSettings: AppSettingsService,
    private val mistralOcrClient: MistralOcrClient,
    private val documentStorage: DocumentStorage,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    @Value("\${storage.type:filesystem}") private val storageType: String,
    @Value("\${storage.upload-dir:uploads}") private val uploadDir: String,
    @Value("\${spring.application.name:recondoc-madaef}") private val appName: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val startedAt: Instant = Instant.now()

    @GetMapping("/health")
    fun health(): Map<String, Any?> {
        val checkedAt = Instant.now()
        return mapOf(
            "checkedAt" to checkedAt.toString(),
            "uptime" to formatUptime(Duration.between(startedAt, checkedAt)),
            "application" to appInfo(),
            "components" to listOf(
                checkDatabase(),
                checkStorage(),
                checkOcr(),
                checkAi(),
                checkJvm()
            )
        )
    }

    private fun appInfo(): Map<String, Any?> = mapOf(
        "name" to appName,
        "startedAt" to startedAt.toString(),
        "javaVersion" to System.getProperty("java.version"),
        "timezone" to java.time.ZoneId.systemDefault().id
    )

    private fun checkDatabase(): Map<String, Any?> {
        var status = "down"
        var latencyMs: Long = -1
        var version: String? = null
        var error: String? = null
        try {
            latencyMs = measureTimeMillis {
                val row = jdbc.queryForMap("SELECT version() as v")
                version = (row["v"] as? String)?.take(80)
            }
            status = "up"
        } catch (e: Exception) {
            error = e.message?.take(200)
            log.warn("DB health probe failed: {}", e.message)
        }
        return mapOf(
            "id" to "db",
            "label" to "PostgreSQL",
            "category" to "core",
            "status" to status,
            "tone" to if (status == "up") "ok" else "error",
            "latencyMs" to latencyMs,
            "details" to mapOf(
                "version" to version,
                "error" to error
            )
        )
    }

    private fun checkStorage(): Map<String, Any?> {
        val isS3 = documentStorage is S3DocumentStorage
        val tone = "ok"
        val details = mutableMapOf<String, Any?>()
        details["type"] = storageType
        details["presignSupported"] = documentStorage.supportsPresignedGet
        if (!isS3) {
            try {
                val dir = Path.of(uploadDir)
                if (Files.exists(dir)) {
                    val store = Files.getFileStore(dir)
                    details["usableSpaceMb"] = store.usableSpace / (1024 * 1024)
                    details["totalSpaceMb"] = store.totalSpace / (1024 * 1024)
                }
            } catch (e: Exception) {
                details["error"] = e.message?.take(200)
            }
        }
        return mapOf(
            "id" to "storage",
            "label" to if (isS3) "Stockage S3" else "Stockage local",
            "category" to "core",
            "status" to "up",
            "tone" to tone,
            "details" to details
        )
    }

    private fun checkOcr(): Map<String, Any?> {
        val mistralConfigured = appSettings.getMistralApiKey().isNotBlank()
        val mistralEnabled = appSettings.isMistralOcrEnabled()
        val mistralLive = mistralOcrClient.isAvailable()
        val status = if (mistralLive) "up" else "degraded"
        val tone = if (mistralLive) "ok" else "warn"
        return mapOf(
            "id" to "ocr",
            "label" to "Pipeline OCR",
            "category" to "pipeline",
            "status" to status,
            "tone" to tone,
            "details" to mapOf(
                "engine" to if (mistralLive) "mistral-ocr" else "tesseract",
                "fallback" to "tesseract",
                "mistralConfigured" to mistralConfigured,
                "mistralEnabled" to mistralEnabled,
                "model" to appSettings.getMistralOcrModel(),
                "mode" to if (mistralLive) "cloud-markdown" else "local-only"
            )
        )
    }

    private fun checkAi(): Map<String, Any?> {
        val keyConfigured = try { appSettings.getAiApiKey().isNotBlank() } catch (_: Exception) { false }
        val enabled = appSettings.isAiEnabled()
        val cb = circuitBreakerRegistry.find("claude").orElse(null)
        val circuit = cb?.state?.name ?: "UNKNOWN"
        val circuitOpen = cb != null && cb.state == CircuitBreaker.State.OPEN
        val failureRate = cb?.metrics?.failureRate ?: -1f
        val status = when {
            !keyConfigured -> "off"
            circuitOpen -> "degraded"
            else -> "up"
        }
        val tone = when (status) {
            "up" -> "ok"
            "degraded" -> "warn"
            else -> "muted"
        }
        return mapOf(
            "id" to "ai",
            "label" to "Claude API",
            "category" to "pipeline",
            "status" to status,
            "tone" to tone,
            "details" to mapOf(
                "apiKeyConfigured" to keyConfigured,
                "enabled" to enabled,
                "model" to appSettings.getAiModel(),
                "circuit" to circuit,
                "failureRate" to failureRate
            )
        )
    }

    private fun checkJvm(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        val freeMb = runtime.freeMemory() / (1024 * 1024)
        val usedMb = totalMb - freeMb
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val usagePct = if (maxMb > 0) (usedMb * 100.0 / maxMb) else 0.0
        val tone = when {
            usagePct > 90 -> "error"
            usagePct > 75 -> "warn"
            else -> "ok"
        }
        return mapOf(
            "id" to "jvm",
            "label" to "JVM memoire",
            "category" to "runtime",
            "status" to if (tone == "error") "degraded" else "up",
            "tone" to tone,
            "details" to mapOf(
                "usedMb" to usedMb,
                "totalMb" to totalMb,
                "maxMb" to maxMb,
                "usagePct" to String.format("%.1f", usagePct).toDouble(),
                "availableProcessors" to runtime.availableProcessors()
            )
        )
    }

    private fun formatUptime(d: Duration): String {
        val days = d.toDays()
        val hours = d.toHoursPart()
        val minutes = d.toMinutesPart()
        return when {
            days > 0 -> "${days}j ${hours}h ${minutes}min"
            hours > 0 -> "${hours}h ${minutes}min"
            else -> "${minutes}min"
        }
    }
}
