package com.madaef.recondoc.service.dossier

import com.madaef.recondoc.entity.dossier.AuditLog
import com.madaef.recondoc.entity.dossier.DossierRuleOverride
import com.madaef.recondoc.entity.dossier.RuleConfig
import com.madaef.recondoc.repository.dossier.AuditLogRepository
import com.madaef.recondoc.repository.dossier.DossierRuleOverrideRepository
import com.madaef.recondoc.repository.dossier.RuleConfigRepository
import com.madaef.recondoc.service.validation.RuleConfigCache
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * Gestion de la configuration des regles (globale + overrides par dossier).
 *
 * Extrait de [com.madaef.recondoc.service.DossierService] pour isoler la surface
 * CRUD de la configuration regles, qui ne partage aucun etat avec le reste du
 * service dossier (pas de lien avec le cycle upload / extraction / export).
 *
 * Interactions :
 *  - [RuleConfigCache] : source de verite en memoire (Caffeine) repliquee sur
 *    les repos JPA. Toute ecriture transite par le cache pour invalidation
 *    coherente.
 *  - [AuditLogRepository] : trace chaque modification (obligation RGPD MADAEF).
 */
@Service
class DossierRuleConfigService(
    private val ruleConfigRepo: RuleConfigRepository,
    private val overrideRepo: DossierRuleOverrideRepository,
    private val ruleConfigCache: RuleConfigCache,
    private val auditLogRepo: AuditLogRepository
) {

    @Transactional(readOnly = true)
    fun getRuleConfig(dossierId: UUID): Map<String, Any> {
        val globals = ruleConfigCache.listGlobal().associate { it.regle to it.enabled }
        val overrides = ruleConfigCache.listOverrides(dossierId).associate { it.regle to it.enabled }
        return mapOf("global" to globals, "overrides" to overrides)
    }

    @Transactional
    fun updateDossierRuleConfig(dossierId: UUID, rules: Map<String, Boolean>) {
        for ((regle, enabled) in rules) {
            val existing = overrideRepo.findByDossierIdAndRegle(dossierId, regle)
            if (existing != null) {
                existing.enabled = enabled
                ruleConfigCache.saveOverride(existing)
            } else {
                ruleConfigCache.saveOverride(DossierRuleOverride(dossierId = dossierId, regle = regle, enabled = enabled))
            }
        }
        auditLogRepo.save(AuditLog(dossierId = dossierId, action = "RULE_CONFIG", detail = "Config regles modifiee: $rules"))
    }

    @Transactional(readOnly = true)
    fun getGlobalRuleConfig(): List<Map<String, Any>> {
        return ruleConfigCache.listGlobal().map { mapOf("regle" to it.regle, "enabled" to it.enabled) }
    }

    @Transactional
    fun updateGlobalRuleConfig(rules: Map<String, Boolean>) {
        for ((regle, enabled) in rules) {
            val existing = ruleConfigRepo.findByRegle(regle)
            if (existing != null) {
                existing.enabled = enabled
                existing.updatedAt = LocalDateTime.now()
                ruleConfigCache.saveGlobal(existing)
            } else {
                ruleConfigCache.saveGlobal(RuleConfig(regle = regle, enabled = enabled))
            }
        }
    }
}
