package com.madaef.recondoc.service.storage

import java.util.UUID

/**
 * Pluggable storage for large OCR text extracts. Keeps the bulky raw text out of
 * Postgres rows so `document.donnees_extraites` stays small and queries stay fast.
 *
 * Returns an opaque key that must be persisted in `document.texte_extrait_key`
 * and passed back to `read()` to fetch the content on demand.
 */
interface ExtractStorage {
    fun write(dossierId: UUID, documentId: UUID, text: String): String
    fun read(key: String): String?
    fun delete(key: String)
}
