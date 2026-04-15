package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "rule_config")
class RuleConfig(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false, unique = true) var regle: String,
    @Column(nullable = false) var enabled: Boolean = true,
    @Column(name = "updated_at") var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "dossier_rule_override")
class DossierRuleOverride(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(name = "dossier_id", nullable = false) var dossierId: UUID,
    @Column(nullable = false) var regle: String,
    @Column(nullable = false) var enabled: Boolean
)
