package com.madaef.recondoc.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "ocr_cache")
class OcrCacheEntry(
    @Id
    @Column(name = "sha256", length = 64, nullable = false)
    var sha256: String = "",

    @Column(name = "cache_version", length = 32, nullable = false)
    var cacheVersion: String = "",

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    var text: String = "",

    @Column(name = "engine", length = 32, nullable = false)
    var engine: String = "",

    @Column(name = "page_count", nullable = false)
    var pageCount: Int = 1,

    @Column(name = "confidence", nullable = false)
    var confidence: Double = -1.0,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "last_hit_at", nullable = false)
    var lastHitAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "hit_count", nullable = false)
    var hitCount: Int = 0
)
