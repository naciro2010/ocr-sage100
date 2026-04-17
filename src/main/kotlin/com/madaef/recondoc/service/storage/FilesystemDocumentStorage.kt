package com.madaef.recondoc.service.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Default storage: writes documents under {upload-dir}/{dossierId}/{fileName}.
 * Pointer stored in Document.cheminFichier is the absolute filesystem path,
 * which matches legacy behavior — no migration needed when switching from
 * the old inline code.
 */
@Component
@ConditionalOnProperty(name = ["storage.type"], havingValue = "filesystem", matchIfMissing = true)
class FilesystemDocumentStorage(
    @Value("\${storage.upload-dir:uploads}") private val uploadDir: String
) : DocumentStorage {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun store(dossierId: UUID, fileName: String, bytes: ByteArray): String {
        val dir = Path.of(uploadDir, dossierId.toString())
        Files.createDirectories(dir)
        val safeName = "${System.currentTimeMillis()}_$fileName"
        val filePath = dir.resolve(safeName)
        Files.write(filePath, bytes)
        log.debug("Wrote {} bytes to {}", bytes.size, filePath)
        return filePath.toString()
    }

    override fun resolveToLocalPath(pointer: String): Path? {
        val p = Path.of(pointer)
        return if (Files.exists(p)) p else null
    }

    override fun delete(pointer: String) {
        try {
            val p = Path.of(pointer)
            if (Files.exists(p)) Files.delete(p)
        } catch (e: Exception) {
            log.warn("Failed to delete {}: {}", pointer, e.message)
        }
    }
}
