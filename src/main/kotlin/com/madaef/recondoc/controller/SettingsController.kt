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
    fun getOcrSettings(): OcrSettingsResponse {
        val rawKey = appSettingsService.getMistralApiKey()
        return OcrSettingsResponse(
            tikaVersion = "3.0.0",
            tesseractAvailable = true,
            languages = "fra+ara",
            preprocessingEnabled = true,
            dpi = 300,
            mistralEnabled = appSettingsService.isMistralOcrEnabled(),
            mistralApiKey = maskApiKey(rawKey),
            mistralApiKeyConfigured = rawKey.isNotBlank(),
            mistralModel = appSettingsService.getMistralOcrModel(),
            mistralBaseUrl = appSettingsService.getMistralBaseUrl()
        )
    }

    @PostMapping("/ocr")
    fun saveOcrSettings(@RequestBody settings: OcrSettingsRequest): OcrSettingsResponse {
        settings.mistralEnabled?.let { appSettingsService.set("ocr.mistral.enabled", it.toString()) }
        if (settings.mistralApiKey != null && !settings.mistralApiKey.startsWith("***")) {
            appSettingsService.set("ocr.mistral.api_key", settings.mistralApiKey)
        }
        settings.mistralModel?.let { appSettingsService.set("ocr.mistral.model", it) }
        settings.mistralBaseUrl?.let { appSettingsService.set("ocr.mistral.base_url", it) }
        return getOcrSettings()
    }

    private fun maskApiKey(key: String): String {
        if (key.isBlank() || key.length < 8) return ""
        return key.take(4) + "***" + key.takeLast(4)
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

data class OcrSettingsRequest(
    val mistralEnabled: Boolean? = null,
    val mistralApiKey: String? = null,
    val mistralModel: String? = null,
    val mistralBaseUrl: String? = null
)

data class OcrSettingsResponse(
    val tikaVersion: String,
    val tesseractAvailable: Boolean,
    val languages: String,
    val preprocessingEnabled: Boolean,
    val dpi: Int,
    val mistralEnabled: Boolean,
    val mistralApiKey: String,
    val mistralApiKeyConfigured: Boolean,
    val mistralModel: String,
    val mistralBaseUrl: String
)
