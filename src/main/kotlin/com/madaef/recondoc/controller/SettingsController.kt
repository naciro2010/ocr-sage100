package com.madaef.recondoc.controller

import com.madaef.recondoc.service.AppSettingsService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/settings")
class SettingsController(
    private val appSettingsService: AppSettingsService
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @GetMapping("/ai")
    fun getAiSettings(): AiSettingsResponse {
        log.debug("Fetching AI settings")
        return AiSettingsResponse(
            enabled = appSettingsService.isAiEnabled(),
            apiKey = maskApiKey(appSettingsService.getAiApiKey()),
            apiKeyConfigured = appSettingsService.getAiApiKey().isNotBlank(),
            model = appSettingsService.getAiModel(),
            baseUrl = appSettingsService.getAiBaseUrl()
        )
    }

    @PostMapping("/ai")
    fun saveAiSettings(@RequestBody settings: AiSettingsRequest): AiSettingsResponse {
        appSettingsService.set("ai.enabled", settings.enabled.toString())
        if (settings.apiKey != null && !settings.apiKey.startsWith("sk-***")) {
            appSettingsService.set("ai.api_key", settings.apiKey)
        }
        settings.model?.let { appSettingsService.set("ai.model", it) }
        settings.baseUrl?.let { appSettingsService.set("ai.base_url", it) }
        return getAiSettings()
    }

    @GetMapping("/ocr")
    fun getOcrInfo(): OcrInfoResponse {
        return OcrInfoResponse(
            tikaVersion = "3.0.0",
            tesseractAvailable = true,
            languages = "fra+ara",
            preprocessingEnabled = true,
            dpi = 300
        )
    }

    private fun maskApiKey(key: String): String {
        if (key.isBlank() || key.length < 8) return ""
        return key.take(7) + "***" + key.takeLast(4)
    }
}

data class AiSettingsRequest(
    val enabled: Boolean = false,
    val apiKey: String? = null,
    val model: String? = null,
    val baseUrl: String? = null
)

data class AiSettingsResponse(
    val enabled: Boolean,
    val apiKey: String,
    val apiKeyConfigured: Boolean,
    val model: String,
    val baseUrl: String
)

data class OcrInfoResponse(
    val tikaVersion: String,
    val tesseractAvailable: Boolean,
    val languages: String,
    val preprocessingEnabled: Boolean,
    val dpi: Int
)
