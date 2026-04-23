package com.madaef.recondoc.config

import com.madaef.recondoc.service.AppSettingsService
import com.madaef.recondoc.service.MistralOcrClient
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
// Spring Boot 4.0 : Health / HealthIndicator ont migre de
// `org.springframework.boot.actuate.health` vers
// `org.springframework.boot.health.contributor` (module spring-boot-health,
// toujours transitivement tire par spring-boot-starter-actuator).
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Expose l'etat du moteur OCR sur /actuator/health/ocr. Tesseract etant embarque
 * dans le conteneur du backend, l'OCR est toujours fonctionnel — on renvoie UP
 * et on ajoute un detail indiquant si Mistral OCR est branche (engine=mistral-ocr)
 * ou si l'on tourne en mode Tesseract pur (engine=tesseract). Cela evite que le
 * healthcheck Railway echoue lorsque la cle Mistral n'est pas configuree.
 */
@Component("ocr")
class OcrHealthIndicator(
    private val mistralOcrClient: MistralOcrClient
) : HealthIndicator {

    override fun health(): Health = try {
        if (mistralOcrClient.isAvailable()) {
            Health.up()
                .withDetail("engine", "mistral-ocr")
                .withDetail("fallback", "tesseract")
                .build()
        } else {
            Health.up()
                .withDetail("engine", "tesseract")
                .withDetail("mode", "local-only")
                .build()
        }
    } catch (e: Exception) {
        Health.up()
            .withDetail("engine", "tesseract")
            .withDetail("mistralError", e.message ?: "unknown")
            .build()
    }
}

/**
 * Expose l'etat de l'integration Claude. On reste UP meme en mode degrade pour
 * que le healthcheck global Railway passe. Les details reportent precisement
 * l'etat (cle configuree, circuit breaker) — utile pour les dashboards.
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
        val cb = circuitBreakerRegistry.find("claude").orElse(null)
        val circuit = cb?.state?.name ?: "UNKNOWN"
        val status = when {
            !keyConfigured -> "not-configured"
            cb != null && cb.state == CircuitBreaker.State.OPEN -> "circuit-open"
            else -> "ready"
        }
        return Health.up()
            .withDetail("status", status)
            .withDetail("circuit", circuit)
            .apply {
                if (cb != null) withDetail("failureRate", cb.metrics.failureRate)
            }
            .build()
    }
}
