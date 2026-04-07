package com.ocrsage.service

import com.ocrsage.entity.AppSetting
import com.ocrsage.repository.AppSettingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AppSettingsService(
    private val repo: AppSettingRepository,
    @Value("\${claude.api-key:}") private val envApiKey: String,
    @Value("\${erp.active:SAGE_1000}") private val envErpActive: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun get(key: String): String? {
        return repo.findBySettingKey(key).map { it.settingValue }.orElse(null)
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
    }

    @Transactional
    fun setAll(settings: Map<String, String?>) {
        settings.forEach { (key, value) -> set(key, value) }
    }

    @Transactional(readOnly = true)
    fun getByPrefix(prefix: String): Map<String, String?> {
        return repo.findBySettingKeyStartingWith(prefix)
            .associate { it.settingKey to it.settingValue }
    }

    // --- AI settings ---

    fun isAiEnabled(): Boolean {
        val dbEnabled = get("ai.enabled")
        return dbEnabled == "true"
    }

    fun getAiApiKey(): String {
        val dbKey = get("ai.api_key")
        return if (!dbKey.isNullOrBlank()) dbKey else envApiKey
    }

    fun getAiModel(): String {
        return getOrDefault("ai.model", "claude-sonnet-4-6")
    }

    fun getAiBaseUrl(): String {
        return getOrDefault("ai.base_url", "https://api.anthropic.com")
    }

    fun hasValidAiConfig(): Boolean {
        return isAiEnabled() && getAiApiKey().isNotBlank()
    }

    // --- ERP settings ---

    fun getActiveErpType(): String {
        val dbType = get("erp.active_type")
        return if (!dbType.isNullOrBlank()) dbType else envErpActive
    }

    fun getErpConfig(erpType: String): Map<String, String?> {
        val prefix = "erp.${erpType.lowercase().replace("_", "")}."
        return getByPrefix(prefix).mapKeys { it.key.removePrefix(prefix) }
    }
}
