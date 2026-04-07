package com.ocrsage.controller

import com.ocrsage.service.AppSettingsService
import com.ocrsage.service.ErpConnectorFactory
import com.ocrsage.service.ErpType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/settings")
class SettingsController(
    private val erpConnectorFactory: ErpConnectorFactory,
    private val appSettingsService: AppSettingsService
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    // --- AI Settings ---

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

    // --- ERP Settings ---

    @GetMapping("/erp")
    fun getErpSettings(): ErpSettingsResponse {
        val activeType = appSettingsService.getActiveErpType()
        return ErpSettingsResponse(
            activeType = activeType,
            availableTypes = ErpType.entries.map { it.name },
            sage1000 = Sage1000Config(
                baseUrl = appSettingsService.get("erp.sage1000.base_url") ?: "",
                apiKey = maskApiKey(appSettingsService.get("erp.sage1000.api_key") ?: ""),
                apiKeyConfigured = (appSettingsService.get("erp.sage1000.api_key") ?: "").isNotBlank(),
                companyCode = appSettingsService.get("erp.sage1000.company_code") ?: "",
                timeout = appSettingsService.get("erp.sage1000.timeout") ?: "30"
            ),
            sageX3 = SageX3Config(
                baseUrl = appSettingsService.get("erp.sagex3.base_url") ?: "",
                clientId = appSettingsService.get("erp.sagex3.client_id") ?: "",
                clientSecret = maskApiKey(appSettingsService.get("erp.sagex3.client_secret") ?: ""),
                clientSecretConfigured = (appSettingsService.get("erp.sagex3.client_secret") ?: "").isNotBlank(),
                folder = appSettingsService.get("erp.sagex3.folder") ?: "MAROC",
                poolAlias = appSettingsService.get("erp.sagex3.pool_alias") ?: "x3"
            ),
            sage50 = Sage50Config(
                baseUrl = appSettingsService.get("erp.sage50.base_url") ?: "",
                username = appSettingsService.get("erp.sage50.username") ?: "",
                password = maskApiKey(appSettingsService.get("erp.sage50.password") ?: ""),
                passwordConfigured = (appSettingsService.get("erp.sage50.password") ?: "").isNotBlank(),
                companyFile = appSettingsService.get("erp.sage50.company_file") ?: "",
                journalCode = appSettingsService.get("erp.sage50.journal_code") ?: "ACH",
                fiscalYear = appSettingsService.get("erp.sage50.fiscal_year") ?: "2024"
            )
        )
    }

    @PostMapping("/erp")
    fun saveErpSettings(@RequestBody settings: ErpSettingsRequest): ErpSettingsResponse {
        settings.activeType?.let { appSettingsService.set("erp.active_type", it) }

        settings.sage1000?.let { cfg ->
            cfg.baseUrl?.let { appSettingsService.set("erp.sage1000.base_url", it) }
            cfg.apiKey?.takeIf { !it.startsWith("sk-***") && !it.startsWith("***") }?.let {
                appSettingsService.set("erp.sage1000.api_key", it)
            }
            cfg.companyCode?.let { appSettingsService.set("erp.sage1000.company_code", it) }
            cfg.timeout?.let { appSettingsService.set("erp.sage1000.timeout", it) }
        }

        settings.sageX3?.let { cfg ->
            cfg.baseUrl?.let { appSettingsService.set("erp.sagex3.base_url", it) }
            cfg.clientId?.let { appSettingsService.set("erp.sagex3.client_id", it) }
            cfg.clientSecret?.takeIf { !it.startsWith("***") }?.let {
                appSettingsService.set("erp.sagex3.client_secret", it)
            }
            cfg.folder?.let { appSettingsService.set("erp.sagex3.folder", it) }
            cfg.poolAlias?.let { appSettingsService.set("erp.sagex3.pool_alias", it) }
        }

        settings.sage50?.let { cfg ->
            cfg.baseUrl?.let { appSettingsService.set("erp.sage50.base_url", it) }
            cfg.username?.let { appSettingsService.set("erp.sage50.username", it) }
            cfg.password?.takeIf { !it.startsWith("***") }?.let {
                appSettingsService.set("erp.sage50.password", it)
            }
            cfg.companyFile?.let { appSettingsService.set("erp.sage50.company_file", it) }
            cfg.journalCode?.let { appSettingsService.set("erp.sage50.journal_code", it) }
            cfg.fiscalYear?.let { appSettingsService.set("erp.sage50.fiscal_year", it) }
        }

        return getErpSettings()
    }

    @PostMapping("/erp/test")
    fun testErpConnection(@RequestBody request: ErpTestRequest): ErpTestResponse {
        return try {
            val erpType = ErpType.valueOf(request.erpType.uppercase().replace("-", "_"))
            val connector = erpConnectorFactory.getConnector(erpType)
            val success = connector.testConnection()
            ErpTestResponse(
                success = success,
                message = if (success) "Connexion reussie a ${request.erpType}" else "Echec de connexion a ${request.erpType}"
            )
        } catch (e: Exception) {
            ErpTestResponse(success = false, message = "Erreur: ${e.message}")
        }
    }

    // --- OCR Info ---

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

// --- Request/Response DTOs ---

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

data class ErpSettingsRequest(
    val activeType: String? = null,
    val sage1000: Sage1000ConfigRequest? = null,
    val sageX3: SageX3ConfigRequest? = null,
    val sage50: Sage50ConfigRequest? = null
)

data class Sage1000ConfigRequest(
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val companyCode: String? = null,
    val timeout: String? = null
)

data class SageX3ConfigRequest(
    val baseUrl: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val folder: String? = null,
    val poolAlias: String? = null
)

data class Sage50ConfigRequest(
    val baseUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val companyFile: String? = null,
    val journalCode: String? = null,
    val fiscalYear: String? = null
)

data class ErpSettingsResponse(
    val activeType: String,
    val availableTypes: List<String>,
    val sage1000: Sage1000Config,
    val sageX3: SageX3Config,
    val sage50: Sage50Config
)

data class Sage1000Config(
    val baseUrl: String,
    val apiKey: String,
    val apiKeyConfigured: Boolean,
    val companyCode: String,
    val timeout: String
)

data class SageX3Config(
    val baseUrl: String,
    val clientId: String,
    val clientSecret: String,
    val clientSecretConfigured: Boolean,
    val folder: String,
    val poolAlias: String
)

data class Sage50Config(
    val baseUrl: String,
    val username: String,
    val password: String,
    val passwordConfigured: Boolean,
    val companyFile: String,
    val journalCode: String,
    val fiscalYear: String
)

data class ErpTestRequest(val erpType: String)
data class ErpTestResponse(val success: Boolean, val message: String)

data class OcrInfoResponse(
    val tikaVersion: String,
    val tesseractAvailable: Boolean,
    val languages: String,
    val preprocessingEnabled: Boolean,
    val dpi: Int
)
