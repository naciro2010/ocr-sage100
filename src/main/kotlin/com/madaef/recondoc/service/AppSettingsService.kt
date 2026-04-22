package com.madaef.recondoc.service

import com.madaef.recondoc.entity.AppSetting
import com.madaef.recondoc.repository.AppSettingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

@Service
class AppSettingsService(
    private val repo: AppSettingRepository,
    @Value("\${claude.api-key:}") private val envApiKey: String,
    @Value("\${ocr.mistral.api-key:}") private val envMistralKey: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, String?>()
    @Volatile private var cacheLoaded = false

    private fun ensureCache() {
        if (!cacheLoaded) {
            synchronized(this) {
                if (!cacheLoaded) {
                    repo.findAll().forEach { cache[it.settingKey] = it.settingValue }
                    cacheLoaded = true
                }
            }
        }
    }

    private fun invalidateCache() {
        cacheLoaded = false
        cache.clear()
    }

    @Transactional(readOnly = true)
    fun get(key: String): String? {
        ensureCache()
        return cache[key]
    }

    @Transactional(readOnly = true)
    fun getOrDefault(key: String, default: String): String {
        return get(key)?.takeIf { it.isNotBlank() } ?: default
    }

    @Transactional
    fun set(key: String, value: String?) {
        val setting = repo.findBySettingKey(key).orElse(null)
        if (setting != null) {
            setting.settingValue = value
            repo.save(setting)
        } else {
            repo.save(AppSetting(settingKey = key, settingValue = value))
        }
        invalidateCache()
    }

    @Transactional
    fun setAll(settings: Map<String, String?>) {
        settings.forEach { (key, value) -> set(key, value) }
    }

    @Transactional(readOnly = true)
    fun getByPrefix(prefix: String): Map<String, String?> {
        ensureCache()
        return cache.filterKeys { it.startsWith(prefix) }
    }

    // --- AI settings ---

    fun isAiEnabled(): Boolean {
        val dbEnabled = get("ai.enabled")
        return dbEnabled == "true"
    }

    fun getAiApiKey(): String {
        val raw = get("ai.api_key")?.takeIf { it.isNotBlank() } ?: envApiKey
        val cleaned = raw.trim()
        if (cleaned != raw) {
            log.warn("La cle API Claude contient des espaces/retours a la ligne en debut ou fin, nettoyee avant envoi")
        }
        return cleaned
    }

    fun getAiModel(): String {
        return getOrDefault("ai.model", "claude-sonnet-4-6")
    }

    fun getAiClassificationModel(): String {
        return get("ai.classification_model")?.takeIf { it.isNotBlank() } ?: getAiModel()
    }

    fun getAiExtractionModel(): String {
        return get("ai.extraction_model")?.takeIf { it.isNotBlank() } ?: getAiModel()
    }

    fun getAiRulesBatchModel(): String {
        return get("ai.rules_batch_model")?.takeIf { it.isNotBlank() } ?: getAiModel()
    }

    fun getAiMaxTokens(kind: String): Int {
        val default = when (kind) {
            "classification" -> 256
            "extraction" -> 8192
            "rules_batch" -> 4096
            else -> 4096
        }
        return get("ai.max_tokens.$kind")?.toIntOrNull()?.takeIf { it > 0 } ?: default
    }

    /**
     * Temperature d'echantillonnage pour Claude (0 = deterministe, 1 = libre).
     * Valeur par defaut 0 : priorite fiabilite (CLAUDE.md "OBJECTIF #1 : FIABILITE 100%")
     * — deux runs du meme document doivent produire la meme extraction.
     * Mettre a -1 (via `ai.temperature = -1`) pour ne pas envoyer le parametre
     * si un modele specifique le rejette avec un 400.
     */
    fun getAiTemperature(): Double {
        val raw = get("ai.temperature")?.toDoubleOrNull()
        return raw ?: 0.0
    }

    fun getAiBaseUrl(): String {
        return getOrDefault("ai.base_url", "https://api.anthropic.com")
    }

    fun hasValidAiConfig(): Boolean {
        return isAiEnabled() && getAiApiKey().isNotBlank()
    }

    // --- Claude pricing ($ per 1M tokens). Defaults match Anthropic public pricing
    // at the time of writing. Override per-model via keys ai.price.<model>.in / .out
    // so finance can update rates when the contract changes, without a redeploy.

    private val defaultPricing: Map<String, Pair<Double, Double>> = mapOf(
        "claude-opus-4-7" to (15.0 to 75.0),
        "claude-sonnet-4-6" to (3.0 to 15.0),
        "claude-sonnet-4-5" to (3.0 to 15.0),
        "claude-haiku-4-5-20251001" to (0.80 to 4.0)
    )

    private val fallbackPricing: Pair<Double, Double> = 3.0 to 15.0

    fun getPricingForModel(model: String): Pair<Double, Double> {
        val inKey = "ai.price.${model}.in"
        val outKey = "ai.price.${model}.out"
        val inPrice = get(inKey)?.toDoubleOrNull()
            ?: defaultPricing[model]?.first
            ?: fallbackPricing.first
        val outPrice = get(outKey)?.toDoubleOrNull()
            ?: defaultPricing[model]?.second
            ?: fallbackPricing.second
        return inPrice to outPrice
    }

    fun getAllPricing(): Map<String, Pair<Double, Double>> {
        val configured = getByPrefix("ai.price.")
        val models = (defaultPricing.keys + configured.keys
            .mapNotNull { k -> k.removePrefix("ai.price.").substringBeforeLast(".").takeIf { it.isNotBlank() } })
            .toSet()
        return models.associateWith { getPricingForModel(it) }
    }

    fun estimateCostUsd(model: String, inputTokens: Long, outputTokens: Long): Double {
        val (inPrice, outPrice) = getPricingForModel(model)
        return (inputTokens / 1_000_000.0) * inPrice + (outputTokens / 1_000_000.0) * outPrice
    }

    // --- Mistral OCR settings (remplace l'ancien microservice PaddleOCR) ---

    fun isMistralOcrEnabled(): Boolean = get("ocr.mistral.enabled") == "true"

    fun getMistralApiKey(): String {
        val raw = get("ocr.mistral.api_key")?.takeIf { it.isNotBlank() } ?: envMistralKey
        return raw.trim()
    }

    fun getMistralBaseUrl(): String = getOrDefault("ocr.mistral.base_url", "https://api.mistral.ai")

    fun getMistralOcrModel(): String = getOrDefault("ocr.mistral.model", "mistral-ocr-latest")

    fun hasValidMistralOcrConfig(): Boolean = isMistralOcrEnabled() && getMistralApiKey().isNotBlank()
}
