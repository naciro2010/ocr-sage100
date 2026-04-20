package com.madaef.recondoc.config

import com.madaef.recondoc.service.AppSettingsService
import com.madaef.recondoc.service.MistralOcrClient
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Surfaces OCR engine state on /actuator/health/ocr. Marked OUT_OF_SERVICE
 * (not DOWN) when Mistral OCR n'est pas configure — Tesseract local reste
 * disponible en fallback, donc le healthcheck Railway reste UP.
 */
@Component("ocr")
class OcrHealthIndicator(
    private val mistralOcrClient: MistralOcrClient
) : HealthIndicator {

    override fun health(): Health = try {
        if (mistralOcrClient.isAvailable()) {
            Health.up().withDetail("engine", "mistral-ocr").build()
        } else {
            Health.status("OUT_OF_SERVICE")
                .withDetail("reason", "Mistral OCR non configure; fallback Tesseract actif")
                .build()
        }
    } catch (e: Exception) {
        Health.status("OUT_OF_SERVICE").withException(e).build()
    }
}

/**
 * Reports the Claude API resilience state. UP when key configured & circuit closed,
 * OUT_OF_SERVICE otherwise — extraction degrades but the app still serves requests.
 */
@Component("claude")
class ClaudeHealthIndicator(
    private val appSettingsService: AppSettingsService,
    private val circuitBreakerRegistry: CircuitBreakerRegistry
) : HealthIndicator {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun health(): Health {
        val keyConfigured = try {
            appSettingsService.getAiApiKey().isNotBlank()
        } catch (e: Exception) {
            log.debug("Claude key check failed: {}", e.message)
            false
        }
        if (!keyConfigured) {
            return Health.status("OUT_OF_SERVICE")
                .withDetail("reason", "CLAUDE_API_KEY not set")
                .build()
        }
        val cb = circuitBreakerRegistry.find("claude").orElse(null)
        if (cb != null && cb.state == CircuitBreaker.State.OPEN) {
            return Health.status("OUT_OF_SERVICE")
                .withDetail("reason", "circuit breaker OPEN")
                .withDetail("failureRate", cb.metrics.failureRate)
                .build()
        }
        return Health.up()
            .withDetail("circuit", cb?.state?.name ?: "UNKNOWN")
            .build()
    }
}
