package com.madaef.recondoc.service.validation

import com.madaef.recondoc.entity.dossier.DossierRuleOverride
import com.madaef.recondoc.entity.dossier.RuleConfig
import com.madaef.recondoc.repository.dossier.DossierRuleOverrideRepository
import com.madaef.recondoc.repository.dossier.RuleConfigRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Cached read path for validation rule configuration. Single dossier
 * validation calls these dozens of times (once per rule × cascade scope);
 * the underlying tables change at most a few times per day, so a 5-min TTL
 * Caffeine cache makes the difference between 50+ DB roundtrips and zero.
 *
 * Mutation methods evict the relevant entries so writes stay correct.
 */
@Service
class RuleConfigCache(
    private val ruleConfigRepo: RuleConfigRepository,
    private val overrideRepo: DossierRuleOverrideRepository
) {

    @Cacheable("ruleConfigGlobal")
    fun listGlobal(): List<RuleConfig> = ruleConfigRepo.findAll()

    @Cacheable("ruleConfigByCode")
    fun findGlobal(regle: String): RuleConfig? = ruleConfigRepo.findByRegle(regle)

    @Cacheable("ruleOverridesByDossier")
    fun listOverrides(dossierId: UUID): List<DossierRuleOverride> =
        overrideRepo.findByDossierId(dossierId)

    @Caching(evict = [
        CacheEvict(value = ["ruleConfigGlobal"], allEntries = true),
        CacheEvict(value = ["ruleConfigByCode"], key = "#regle")
    ])
    fun saveGlobal(config: RuleConfig): RuleConfig = ruleConfigRepo.save(config)

    @CacheEvict(value = ["ruleOverridesByDossier"], key = "#override.dossierId")
    fun saveOverride(override: DossierRuleOverride): DossierRuleOverride =
        overrideRepo.save(override)
}
