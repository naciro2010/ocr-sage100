package com.madaef.recondoc.service

import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Client REST pour l'API Mistral Document AI (mistral-ocr-latest).
 *
 * Flow:
 *  1. POST /v1/files (multipart, purpose=ocr) -> file_id
 *  2. GET  /v1/files/{id}/url               -> signed URL temporaire
 *  3. POST /v1/ocr                          -> pages[].markdown
 *  4. DELETE /v1/files/{id}                 -> nettoyage (best-effort)
 *
 * Le resultat est concatene en Markdown, ideal pour l'extraction Claude en aval
 * (tableaux preserves, layout lisible).
 */
@Service
class MistralOcrClient(
    private val appSettingsService: AppSettingsService
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val clientCache = ConcurrentHashMap<String, WebClient>()

    fun isAvailable(): Boolean {
        return appSettingsService.isMistralOcrEnabled() &&
            appSettingsService.getMistralApiKey().isNotBlank()
    }

    private fun getClient(): WebClient {
        val apiKey = appSettingsService.getMistralApiKey()
        val baseUrl = appSettingsService.getMistralBaseUrl()
        val cacheKey = "$baseUrl|${apiKey.takeLast(8)}"
        return clientCache.computeIfAbsent(cacheKey) {
            val httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(120))
            WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .defaultHeader("Authorization", "Bearer $apiKey")
                .codecs { it.defaultCodecs().maxInMemorySize(50 * 1024 * 1024) }
                .build()
        }
    }

    fun ocr(filePath: Path): MistralOcrResult {
        if (!isAvailable()) throw IllegalStateException("Mistral OCR not configured")

        val started = System.currentTimeMillis()
        val fileId = uploadFile(filePath)
        try {
            val signedUrl = getSignedUrl(fileId)
            return callOcr(signedUrl, filePath, started)
        } finally {
            deleteFile(fileId)
        }
    }

    private fun uploadFile(filePath: Path): String {
        val fileName = filePath.fileName.toString()
        val fileSize = Files.size(filePath)
        log.info("Uploading {} ({} KB) to Mistral /v1/files", fileName, fileSize / 1024)

        val body = MultipartBodyBuilder().apply {
            part("file", FileSystemResource(filePath)).contentType(mediaTypeFor(fileName))
            part("purpose", "ocr")
        }.build()

        // Ne pas forcer Content-Type ni Content-Disposition : BodyInserters.fromMultipartData
        // genere le boundary et les parts correctement. Un override explicite casse le parse
        // cote Mistral (422 "file required") — verifie en prod avant cette PR.
        val response = getClient().post()
            .uri("/v1/files")
            .body(BodyInserters.fromMultipartData(body))
            .retrieve()
            .onStatus({ it.isError }, ::mapError)
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(60))
            .block() ?: throw RuntimeException("Empty response from Mistral /v1/files")

        return response["id"] as? String
            ?: throw RuntimeException("No file id in Mistral upload response")
    }

    private fun getSignedUrl(fileId: String): String {
        val response = getClient().get()
            .uri("/v1/files/$fileId/url?expiry=24")
            .retrieve()
            .onStatus({ it.isError }, ::mapError)
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(30))
            .block() ?: throw RuntimeException("Empty response from Mistral /v1/files/{id}/url")

        return response["url"] as? String
            ?: throw RuntimeException("No url in Mistral signed url response")
    }

    private fun callOcr(signedUrl: String, filePath: Path, started: Long): MistralOcrResult {
        val fileName = filePath.fileName.toString()
        val isImage = isImageFile(fileName)
        val model = appSettingsService.getMistralOcrModel()

        val document: Map<String, String> = if (isImage) {
            mapOf("type" to "image_url", "image_url" to signedUrl)
        } else {
            mapOf("type" to "document_url", "document_url" to signedUrl)
        }

        val requestBody = mapOf(
            "model" to model,
            "document" to document,
            "include_image_base64" to false
        )

        @Suppress("UNCHECKED_CAST")
        val response = getClient().post()
            .uri("/v1/ocr")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .onStatus({ it.isError }, ::mapError)
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(120))
            .block() ?: throw RuntimeException("Empty response from Mistral /v1/ocr")

        val pages = response["pages"] as? List<Map<String, Any>> ?: emptyList()
        val markdown = pages.joinToString("\n\n") { (it["markdown"] as? String).orEmpty() }.trim()
        val pageCount = pages.size
        val usage = response["usage_info"] as? Map<*, *>
        val pagesBilled = (usage?.get("pages_processed") as? Number)?.toInt() ?: pageCount
        val durationMs = System.currentTimeMillis() - started

        log.info(
            "Mistral OCR: {} pages, {} chars, {} pages facturees, {}ms",
            pageCount, markdown.length, pagesBilled, durationMs
        )

        return MistralOcrResult(
            markdown = markdown,
            pageCount = pageCount,
            pagesBilled = pagesBilled,
            durationMs = durationMs
        )
    }

    private fun deleteFile(fileId: String) {
        try {
            getClient().delete()
                .uri("/v1/files/$fileId")
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .block()
        } catch (e: Exception) {
            log.debug("Mistral file cleanup failed for {}: {}", fileId, e.message)
        }
    }

    private fun mapError(resp: org.springframework.web.reactive.function.client.ClientResponse) =
        resp.bodyToMono(String::class.java).defaultIfEmpty("")
            .map { body ->
                val status: HttpStatusCode = resp.statusCode()
                log.error("Mistral OCR {} error: {}", status.value(), body.take(500))
                val msg = when {
                    status.value() == 401 ->
                        "Cle API Mistral invalide. Verifiez dans Parametres > Pipeline OCR."
                    status.value() == 429 ->
                        "Quota Mistral OCR atteint. Reessayez plus tard."
                    status.value() in 500..599 ->
                        "Service Mistral OCR indisponible (${status.value()}). Fallback Tesseract."
                    else -> "Erreur Mistral OCR ${status.value()}: ${body.take(200)}"
                }
                WebClientResponseException.create(status.value(), msg, resp.headers().asHttpHeaders(), byteArrayOf(), null)
            }

    private fun mediaTypeFor(fileName: String): MediaType {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".pdf") -> MediaType.APPLICATION_PDF
            lower.endsWith(".png") -> MediaType.IMAGE_PNG
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> MediaType.IMAGE_JPEG
            lower.endsWith(".tif") || lower.endsWith(".tiff") -> MediaType.parseMediaType("image/tiff")
            else -> MediaType.APPLICATION_OCTET_STREAM
        }
    }

    private fun isImageFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".tif") || lower.endsWith(".tiff") || lower.endsWith(".bmp") ||
            lower.endsWith(".gif") || lower.endsWith(".webp")
    }
}

data class MistralOcrResult(
    val markdown: String,
    val pageCount: Int,
    val pagesBilled: Int,
    val durationMs: Long
)
