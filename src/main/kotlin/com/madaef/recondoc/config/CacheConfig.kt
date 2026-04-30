package com.madaef.recondoc.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/**
 * Caffeine in-process cache. Targets hot config lookups (validation rule
 * config) that fire dozens of times per dossier validation. Stays in-process
 * because Railway runs a single replica today; if we ever scale out, swap for
 * Redis without touching call sites (just @Cacheable annotations).
 */
@Configuration
@EnableCaching
class CacheConfig {

    @Bean
    fun cacheManager(): CacheManager {
        val mgr = CaffeineCacheManager(
            "ruleConfigGlobal",      // List<RuleConfig> for the whole app
            "ruleConfigByCode",      // RuleConfig by rule code
            "ruleOverridesByDossier" // List<DossierRuleOverride> per dossier
        )
        mgr.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1000)
                .recordStats()
        )

        // Cache dedie pour le journal d'audit : TTL plus court (15s) car il
        // change a chaque action utilisateur (audit() appele a la creation,
        // validation, rerun, correction, finalize, ...). On evite la
        // complexite d'un @CacheEvict cross-bean (les self-calls dans Spring
        // ne traversent pas le proxy AOP) en acceptant 15s de retard sur
        // le journal — il est consultatif, pas critique pour la fiabilite
        // des verdicts. Gros gain sur les bursts de GET (StrictMode dev,
        // navigations rapides, polling SSE qui rappelle l'audit panel).
        mgr.registerCustomCache(
            "auditLogByDossier",
            Caffeine.newBuilder()
                .expireAfterWrite(15, TimeUnit.SECONDS)
                .maximumSize(500)
                .recordStats()
                .build()
        )
        return mgr
    }
}
