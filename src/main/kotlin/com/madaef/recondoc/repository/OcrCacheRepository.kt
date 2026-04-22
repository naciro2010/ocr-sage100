package com.madaef.recondoc.repository

import com.madaef.recondoc.entity.OcrCacheEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface OcrCacheRepository : JpaRepository<OcrCacheEntry, String> {

    @Modifying
    @Query("""
        UPDATE OcrCacheEntry c
           SET c.hitCount = c.hitCount + 1,
               c.lastHitAt = :now
         WHERE c.sha256 = :sha256
    """)
    fun markHit(@Param("sha256") sha256: String, @Param("now") now: LocalDateTime): Int
}
