package com.madaef.recondoc.service.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * S3-compatible storage. Works with AWS S3, Wasabi, Backblaze B2, Scaleway,
 * MinIO, etc. — anything that speaks the S3 API.
 *
 * Keys are shaped as `dossiers/{dossierId}/{timestamp}_{fileName}` so that
 * prefix-based listings (per-dossier cleanup, lifecycle by prefix) are cheap
 * and the timestamp avoids collisions when a file is re-uploaded with the
 * same name.
 *
 * Local cache under {upload-dir}/_s3cache/{key} lets OCR / Tika / QR code
 * readers keep using `java.nio.file.Path` without change. The cache is
 * populated on upload (same bytes already in memory) and lazily rebuilt
 * on read if missing — survives Railway redeploys provided the cache sits
 * on a persistent Volume; otherwise we just re-fetch from S3, which is the
 * point of using S3 in the first place.
 */
@Component
@ConditionalOnProperty(name = ["storage.type"], havingValue = "s3")
class S3DocumentStorage(
    @Value("\${storage.upload-dir:uploads}") uploadDir: String,
    @Value("\${storage.s3.bucket}") private val bucket: String,
    @Value("\${storage.s3.region:eu-west-1}") region: String,
    @Value("\${storage.s3.endpoint:}") endpoint: String,
    @Value("\${storage.s3.access-key:}") accessKey: String,
    @Value("\${storage.s3.secret-key:}") secretKey: String,
    @Value("\${storage.s3.force-path-style:false}") private val forcePathStyle: Boolean
) : DocumentStorage {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cacheDir: Path = Path.of(uploadDir, "_s3cache")

    private val credentials = if (accessKey.isNotBlank() && secretKey.isNotBlank()) {
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
    } else {
        DefaultCredentialsProvider.create()
    }

    private val s3: S3Client by lazy {
        val b = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .forcePathStyle(forcePathStyle)
        if (endpoint.isNotBlank()) b.endpointOverride(URI.create(endpoint))
        b.build()
    }

    private val presigner: S3Presigner by lazy {
        val b = S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
        if (endpoint.isNotBlank()) b.endpointOverride(URI.create(endpoint))
        b.build()
    }

    override fun store(dossierId: UUID, fileName: String, bytes: ByteArray): String {
        val safeName = "${System.currentTimeMillis()}_$fileName"
        val key = "dossiers/$dossierId/$safeName"

        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(guessContentType(fileName))
                .cacheControl("private, max-age=3600")
                .build(),
            software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes)
        )

        // Warm the local cache with the bytes we already have in memory so the
        // OCR pipeline runs without a roundtrip back to S3.
        val cachePath = cachePathFor(key)
        Files.createDirectories(cachePath.parent)
        Files.write(cachePath, bytes)

        log.debug("Uploaded {} bytes to s3://{}/{}", bytes.size, bucket, key)
        return key
    }

    override fun resolveToLocalPath(pointer: String): Path? {
        val key = normalizeKey(pointer) ?: return resolveLegacy(pointer)
        val cached = cachePathFor(key)
        if (Files.exists(cached)) return cached
        return try {
            Files.createDirectories(cached.parent)
            s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build(),
                cached
            )
            cached
        } catch (_: NoSuchKeyException) {
            null
        } catch (e: Exception) {
            log.warn("Failed to fetch s3://{}/{}: {}", bucket, key, e.message)
            null
        }
    }

    /**
     * Legacy Document rows written before S3 was enabled still point at absolute
     * filesystem paths. Resolve them locally when possible, otherwise return null.
     */
    private fun resolveLegacy(pointer: String): Path? {
        val p = Path.of(pointer)
        return if (Files.exists(p)) p else null
    }

    override fun delete(pointer: String) {
        val key = normalizeKey(pointer) ?: return
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
        } catch (e: Exception) {
            log.warn("Failed to delete s3://{}/{}: {}", bucket, key, e.message)
        }
        try {
            val cached = cachePathFor(key)
            if (Files.exists(cached)) Files.delete(cached)
        } catch (_: Exception) { }
    }

    override fun presignGet(pointer: String, ttl: Duration): String? {
        val key = normalizeKey(pointer) ?: return null
        val request = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build()
        return presigner.presignGetObject(request).url().toString()
    }

    override val supportsPresignedGet: Boolean get() = true

    private fun cachePathFor(key: String): Path = cacheDir.resolve(key)

    private fun normalizeKey(pointer: String): String? {
        if (pointer.isBlank()) return null
        if (pointer.startsWith("dossiers/")) return pointer
        if (pointer.startsWith("s3://")) {
            val withoutScheme = pointer.removePrefix("s3://")
            val slash = withoutScheme.indexOf('/')
            return if (slash >= 0) withoutScheme.substring(slash + 1) else null
        }
        // Looks like a filesystem path — not an S3 key.
        return null
    }

    private fun guessContentType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".tif") || lower.endsWith(".tiff") -> "image/tiff"
            lower.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
