package com.madaef.recondoc.service.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Default storage: writes each extract under {upload-dir}/extracts/{dossierId}/{documentId}.txt.
 * On Railway this sits on the same persistent Volume as the original PDFs — no extra infra
 * required. When an S3-compatible backend is provisioned later, swap this bean for an
 * implementation wrapping the AWS SDK and flip a config flag.
 */
@Component
class FilesystemExtractStorage(
    @Value("\${storage.upload-dir:uploads}") private val uploadDir: String,
    @Value("\${storage.extracts-subdir:extracts}") private val extractsSubdir: String
) : ExtractStorage {

    private val log = LoggerFactory.getLogger(javaClass)
    private val root: Path = Path.of(uploadDir, extractsSubdir)

    override fun write(dossierId: UUID, documentId: UUID, text: String): String {
        val dir = root.resolve(dossierId.toString())
        Files.createDirectories(dir)
        val file = dir.resolve("$documentId.txt")
        Files.write(file, text.toByteArray(StandardCharsets.UTF_8))
        val key = "$dossierId/$documentId.txt"
        log.debug("Stored {} chars at {}", text.length, key)
        return key
    }

    override fun read(key: String): String? {
        val file = root.resolve(key)
        return if (Files.exists(file)) Files.readString(file, StandardCharsets.UTF_8) else null
    }

    override fun delete(key: String) {
        try {
            val file = root.resolve(key)
            if (Files.exists(file)) Files.delete(file)
        } catch (e: Exception) {
            log.warn("Failed to delete extract {}: {}", key, e.message)
        }
    }
}
