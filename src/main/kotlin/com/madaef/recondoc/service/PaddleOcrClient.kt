package com.madaef.recondoc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Client REST pour le microservice PaddleOCR (FastAPI).
 * Envoie un fichier PDF/image et recoit le texte extrait + metadonnees.
 */
@Service
class PaddleOcrClient(
    @Value("\${ocr.paddle.base-url:}") private val baseUrl: String,
    @Value("\${ocr.paddle.timeout:120}") private val timeoutSec: Long,
    @Value("\${ocr.paddle.lang:fr}") private val defaultLang: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(50 * 1024 * 1024) }
            .build()
    }

    /**
     * Returns true if the PaddleOCR service is configured and reachable.
     */
    fun isAvailable(): Boolean {
        if (baseUrl.isBlank()) return false
        return try {
            val response = webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(Map::class.java)
                .timeout(Duration.ofSeconds(5))
                .block()
            response?.get("status") == "ok"
        } catch (e: Exception) {
            log.debug("PaddleOCR service not reachable at {}: {}", baseUrl, e.message)
            false
        }
    }

    /**
     * Send a file to PaddleOCR and get structured OCR result.
     */
    fun ocr(filePath: Path, lang: String = defaultLang): PaddleOcrResult {
        val fileName = filePath.fileName.toString()
        val fileBytes = Files.readAllBytes(filePath)

        log.info("Sending {} ({} KB) to PaddleOCR at {}", fileName, fileBytes.size / 1024, baseUrl)

        val bodyBuilder = MultipartBodyBuilder()
        bodyBuilder.part("file", object : ByteArrayResource(fileBytes) {
            override fun getFilename() = fileName
        }).contentType(if (fileName.lowercase().endsWith(".pdf")) MediaType.APPLICATION_PDF else MediaType.IMAGE_PNG)

        @Suppress("UNCHECKED_CAST")
        val response = webClient.post()
            .uri { it.path("/ocr").queryParam("lang", lang).build() }
            .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
            .retrieve()
            .bodyToMono(Map::class.java)
            .timeout(Duration.ofSeconds(timeoutSec))
            .block() ?: throw RuntimeException("Empty response from PaddleOCR")

        val text = response["text"] as? String ?: ""
        val pageCount = (response["page_count"] as? Number)?.toInt() ?: 1
        val durationMs = (response["duration_ms"] as? Number)?.toLong() ?: 0
        val pages = response["pages"] as? List<Map<String, Any>> ?: emptyList()

        // Compute average confidence across all pages
        val avgConfidence = pages
            .mapNotNull { (it["avg_confidence"] as? Number)?.toDouble() }
            .takeIf { it.isNotEmpty() }
            ?.average() ?: 0.0

        log.info("PaddleOCR result: {} chars, {} pages, {:.1f}% confidence, {}ms",
            text.length, pageCount, avgConfidence * 100, durationMs)

        return PaddleOcrResult(
            text = text,
            pageCount = pageCount,
            confidence = avgConfidence,
            durationMs = durationMs
        )
    }
}

data class PaddleOcrResult(
    val text: String,
    val pageCount: Int,
    val confidence: Double,
    val durationMs: Long
)
