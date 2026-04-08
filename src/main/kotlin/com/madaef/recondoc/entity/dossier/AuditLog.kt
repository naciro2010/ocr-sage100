package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "audit_log", indexes = [
    Index(name = "idx_audit_dossier", columnList = "dossier_id"),
    Index(name = "idx_audit_date", columnList = "date_action")
])
class AuditLog(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(name = "dossier_id") var dossierId: UUID? = null,
    @Column(nullable = false) var action: String,
    @Column(columnDefinition = "TEXT") var detail: String? = null,
    @Column(name = "utilisateur") var utilisateur: String? = null,
    @Column(name = "date_action", nullable = false) var dateAction: LocalDateTime = LocalDateTime.now()
)
