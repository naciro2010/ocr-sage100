package com.madaef.recondoc.service.storage

import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * Storage abstraction for original uploaded documents (PDF / images).
 *
 * The existing code base uses `Document.cheminFichier` as both the storage
 * pointer and the local filesystem path. With S3 enabled the two diverge:
 *  - the pointer becomes an S3 object key (e.g. `dossiers/{dossierId}/{fileName}`)
 *  - the local path is a cache populated at upload time and re-hydrated on
 *    demand from S3 when missing (e.g. after a Railway redeploy).
 *
 * `resolveToLocalPath` returns a Path usable by OCR / QR / Tika code that
 * already expects `java.nio.file.Path`. The filesystem impl returns the path
 * directly; the S3 impl downloads once into a local cache directory.
 */
interface DocumentStorage {

    /**
     * Store a freshly uploaded document. Writes the bytes somewhere durable
     * and returns the stable pointer to persist in `Document.cheminFichier`.
     */
    fun store(dossierId: UUID, fileName: String, bytes: ByteArray): String

    /**
     * Stocke un document contractuel source d'un Engagement (marche, BC cadre,
     * contrat). Ces documents ne sont pas rattaches a un dossier ; on les
     * regroupe par reference d'engagement via un UUID deterministe.
     *
     * L'impl par defaut reutilise `store` sous un UUID derive de la reference
     * pour maintenir la meme arborescence de stockage (filesystem / S3).
     */
    fun storeEngagementDocument(engagementRef: String, fileName: String, bytes: ByteArray): String {
        val pseudoId = UUID.nameUUIDFromBytes("engagement/$engagementRef".toByteArray())
        return store(pseudoId, fileName, bytes)
    }

    /**
     * Resolve a stored pointer to a local filesystem path, fetching from
     * remote storage if necessary. Returns null if the object doesn't exist.
     */
    fun resolveToLocalPath(pointer: String): Path?

    /**
     * Delete the stored object. Best-effort; failures are logged but not
     * surfaced because storage deletion must never break dossier cleanup.
     */
    fun delete(pointer: String)

    /**
     * Presigned GET URL for direct browser access. Returns null when the
     * backend does not support presigning (filesystem impl — caller falls
     * back to streaming through the Spring controller).
     */
    fun presignGet(pointer: String, ttl: Duration = Duration.ofMinutes(10)): String? = null

    /**
     * Whether this backend can serve bytes directly to clients via URLs.
     * Frontend uses this to decide between presigned redirect vs. backend proxy.
     */
    val supportsPresignedGet: Boolean get() = false
}
