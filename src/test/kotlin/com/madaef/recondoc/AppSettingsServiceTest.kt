package com.madaef.recondoc

import com.madaef.recondoc.entity.AppSetting
import com.madaef.recondoc.repository.AppSettingRepository
import com.madaef.recondoc.service.AppSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.assertEquals

class AppSettingsServiceTest {

    private lateinit var repo: AppSettingRepository
    private lateinit var service: AppSettingsService

    @BeforeEach
    fun setup() {
        repo = mock(AppSettingRepository::class.java)
    }

    private fun buildService(stored: Map<String, String?>): AppSettingsService {
        `when`(repo.findAll()).thenReturn(stored.map { (k, v) -> AppSetting(settingKey = k, settingValue = v) })
        stored.forEach { (k, v) ->
            `when`(repo.findBySettingKey(k)).thenReturn(Optional.of(AppSetting(settingKey = k, settingValue = v)))
        }
        return AppSettingsService(repo, envApiKey = "", envMistralKey = "")
    }

    @Test
    fun `classification defaults to haiku`() {
        val svc = buildService(emptyMap())
        assertEquals("claude-haiku-4-5-20251001", svc.getAiClassificationModel())
    }

    @Test
    fun `classification override wins`() {
        val svc = buildService(mapOf("ai.classification_model" to "claude-sonnet-4-6"))
        assertEquals("claude-sonnet-4-6", svc.getAiClassificationModel())
    }

    @Test
    fun `extraction falls back to ai_model when extraction_model not set`() {
        val svc = buildService(mapOf("ai.model" to "claude-opus-4-7"))
        assertEquals("claude-opus-4-7", svc.getAiExtractionModel())
    }

    @Test
    fun `extraction dedicated override wins over ai_model`() {
        val svc = buildService(mapOf(
            "ai.model" to "claude-opus-4-7",
            "ai.extraction_model" to "claude-sonnet-4-6"
        ))
        assertEquals("claude-sonnet-4-6", svc.getAiExtractionModel())
    }

    @Test
    fun `rules batch falls back to ai_model`() {
        val svc = buildService(mapOf("ai.model" to "claude-sonnet-4-6"))
        assertEquals("claude-sonnet-4-6", svc.getAiRulesBatchModel())
    }

    @Test
    fun `max_tokens defaults by kind`() {
        val svc = buildService(emptyMap())
        assertEquals(256, svc.getAiMaxTokens("classification"))
        assertEquals(8192, svc.getAiMaxTokens("extraction"))
        assertEquals(4096, svc.getAiMaxTokens("rules_batch"))
    }

    @Test
    fun `max_tokens override per kind`() {
        val svc = buildService(mapOf(
            "ai.max_tokens.classification" to "128",
            "ai.max_tokens.extraction" to "4096"
        ))
        assertEquals(128, svc.getAiMaxTokens("classification"))
        assertEquals(4096, svc.getAiMaxTokens("extraction"))
        assertEquals(4096, svc.getAiMaxTokens("rules_batch"))
    }

    @Test
    fun `max_tokens invalid or zero falls back to default`() {
        val svc = buildService(mapOf(
            "ai.max_tokens.classification" to "abc",
            "ai.max_tokens.extraction" to "0"
        ))
        assertEquals(256, svc.getAiMaxTokens("classification"))
        assertEquals(8192, svc.getAiMaxTokens("extraction"))
    }
}
