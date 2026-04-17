package com.madaef.recondoc.service

import com.madaef.recondoc.entity.dossier.TypeDocument
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

/**
 * Full-text search over OCR'd documents using the search_tsv tsvector column
 * provisioned by V17. Postgres-only: H2 in tests doesn't support tsvector,
 * but the test profile never invokes this service.
 *
 * Falls back to ILIKE on filename + texte_extrait if the FTS query throws,
 * so a misconfigured environment still returns *something*.
 */
@Service
class DocumentSearchService(
    private val entityManager: EntityManager
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Hit(
        val documentId: UUID,
        val dossierId: UUID,
        val dossierReference: String,
        val nomFichier: String,
        val typeDocument: TypeDocument,
        val dateUpload: LocalDateTime,
        val rank: Double
    )

    fun search(query: String, limit: Int = 50): List<Hit> {
        if (query.isBlank()) return emptyList()
        return try {
            ftsSearch(query, limit)
        } catch (e: Exception) {
            log.warn("FTS query failed, falling back to ILIKE: {}", e.message)
            likeSearch(query, limit)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ftsSearch(query: String, limit: Int): List<Hit> {
        val sql = """
            SELECT d.id, dp.id, dp.reference, d.nom_fichier, d.type_document, d.date_upload,
                   ts_rank(d.search_tsv, plainto_tsquery('simple', :q)) AS rank
            FROM document d
            JOIN dossier_paiement dp ON dp.id = d.dossier_id
            WHERE d.search_tsv @@ plainto_tsquery('simple', :q)
            ORDER BY rank DESC
            LIMIT :limit
        """.trimIndent()
        val rows = entityManager.createNativeQuery(sql)
            .setParameter("q", query)
            .setParameter("limit", limit)
            .resultList as List<Array<Any?>>
        return rows.map { mapRow(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun likeSearch(query: String, limit: Int): List<Hit> {
        val sql = """
            SELECT d.id, dp.id, dp.reference, d.nom_fichier, d.type_document, d.date_upload, 0.0 AS rank
            FROM document d
            JOIN dossier_paiement dp ON dp.id = d.dossier_id
            WHERE LOWER(d.nom_fichier) LIKE LOWER(:like)
               OR LOWER(COALESCE(d.texte_extrait, '')) LIKE LOWER(:like)
            ORDER BY d.date_upload DESC
            LIMIT :limit
        """.trimIndent()
        val rows = entityManager.createNativeQuery(sql)
            .setParameter("like", "%$query%")
            .setParameter("limit", limit)
            .resultList as List<Array<Any?>>
        return rows.map { mapRow(it) }
    }

    private fun mapRow(row: Array<Any?>): Hit {
        val docIdRaw = row[0]
        val dosIdRaw = row[1]
        val docId = if (docIdRaw is UUID) docIdRaw else UUID.fromString(docIdRaw.toString())
        val dosId = if (dosIdRaw is UUID) dosIdRaw else UUID.fromString(dosIdRaw.toString())
        val typeRaw = row[4]?.toString() ?: TypeDocument.INCONNU.name
        val type = runCatching { TypeDocument.valueOf(typeRaw) }.getOrDefault(TypeDocument.INCONNU)
        val dateRaw = row[5]
        val date = when (dateRaw) {
            is LocalDateTime -> dateRaw
            is java.sql.Timestamp -> dateRaw.toLocalDateTime()
            else -> LocalDateTime.now()
        }
        return Hit(
            documentId = docId,
            dossierId = dosId,
            dossierReference = row[2]?.toString() ?: "",
            nomFichier = row[3]?.toString() ?: "",
            typeDocument = type,
            dateUpload = date,
            rank = (row[6] as? Number)?.toDouble() ?: 0.0
        )
    }
}
