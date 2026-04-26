package com.madaef.recondoc.config

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Strategie de migration Flyway defensive : repair() avant migrate().
 *
 * Contexte (incident Railway 2026-04-26) : Postgres applique
 * `statement_timeout=10s` au niveau session — si une migration ALTER TABLE
 * est interrompue (timeout, OOM container, restart force), Flyway laisse
 * une ligne `Failed` dans `flyway_schema_history`. Cet etat **bloque toutes
 * les migrations suivantes** au prochain demarrage : V36 ne s'applique pas,
 * la colonne `attestation_fiscale.type_attestation` reste absente, et
 * Hibernate (ddl-auto: validate) refuse de booter.
 *
 * `flyway.repair()` :
 *  - supprime les entrees `Failed` de l'historique (la migration sera
 *    reappliquee proprement) ;
 *  - realigne les checksums si une migration deja appliquee a ete editee
 *    (rare, mais evite les false-positives au boot).
 *
 * Cette strategie est idempotente : sur une base saine, repair() est un
 * no-op, puis migrate() detecte 0 migration en attente. Cout : un round-trip
 * SQL au boot. Benefice : auto-recovery sans intervention humaine.
 *
 * Profil `test` exclu : H2 in-memory + Flyway desactive cote test
 * (`application-test.yml`).
 */
@Configuration
@Profile("!test")
class FlywayConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun flywayMigrationStrategy(): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway ->
        try {
            flyway.repair()
            log.info("Flyway repair effectue (idempotent si rien a reparer)")
        } catch (e: Exception) {
            log.warn("Flyway repair a echoue (non bloquant, on tente migrate quand meme): {}", e.message)
        }

        val result = flyway.migrate()
        log.info(
            "Flyway migrate: {} migrations appliquees (target={}, success={})",
            result.migrationsExecuted,
            result.targetSchemaVersion,
            result.success
        )
    }
}
