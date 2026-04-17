package com.madaef.recondoc.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

/**
 * One row per Claude API call. Cheap to write, lets us answer "how much did
 * dossier X cost?" and surface monthly burn in the admin dashboard.
 */
@Entity
@Table(
    name = "claude_usage",
    indexes = [
        Index(name = "idx_claude_usage_dossier", columnList = "dossier_id"),
        Index(name = "idx_claude_usage_created", columnList = "created_at")
    ]
)
class ClaudeUsage(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(name = "dossier_id") var dossierId: UUID? = null,
    @Column(name = "document_id") var documentId: UUID? = null,
    @Column(nullable = false) var model: String,
    @Column(name = "input_tokens", nullable = false) var inputTokens: Int = 0,
    @Column(name = "output_tokens", nullable = false) var outputTokens: Int = 0,
    @Column(name = "duration_ms", nullable = false) var durationMs: Long = 0,
    @Column(nullable = false) var success: Boolean = true,
    @Column(columnDefinition = "TEXT") var error: String? = null,
    @Column(name = "created_at", nullable = false) var createdAt: LocalDateTime = LocalDateTime.now()
)
