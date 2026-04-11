package com.madaef.recondoc.service

import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.ImageType
import org.apache.tika.Tika
import org.apache.tika.config.TikaConfig
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Pipeline OCR a 3 moteurs avec cascade intelligente :
 * 1. Tika       : extraction texte natif (PDF numeriques) - instantane
 * 2. PaddleOCR  : OCR deep learning via microservice Python - meilleure precision
 * 3. Tesseract  : OCR local fallback si PaddleOCR indisponible
 */
@Service
class OcrService(
    private val preprocessingService: ImagePreprocessingService,
    private val textNormalizationService: TextNormalizationService,
    private val paddleOcrClient: PaddleOcrClient,
    @Value("\${ocr.tesseract.data-path:/usr/share/tessdata}") private val tessDataPath: String,
    @Value("\${ocr.tesseract.languages:fra+ara}") private val languages: String,
    @Value("\${ocr.tesseract.dpi:300}") private val dpi: Int,
    @Value("\${ocr.tesseract.oem:1}") private val oem: Int,
    @Value("\${ocr.confidence-threshold:30}") private val confidenceThreshold: Int
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tika: Tika = try {
        val configStream = javaClass.getResourceAsStream("/tika-config.xml")
        if (configStream != null) {
            val config = TikaConfig(configStream)
            log.info("Tika initialized with custom config (TesseractOCR disabled)")
            Tika(config)
        } else {
            log.info("Tika initialized with default config")
            Tika()
        }
    } catch (e: Exception) {
        log.warn("Failed to load tika-config.xml, using defaults: {}", e.message)
        Tika()
    }

    private var tesseractAvailable: Boolean = false
    private var resolvedTessDataPath: String = tessDataPath
    private var paddleAvailable: Boolean = false

    init {
        tesseractAvailable = checkTesseractAvailability()
        if (tesseractAvailable) {
            log.info("Tesseract OCR available (dataPath={}, languages={})", resolvedTessDataPath, languages)
        }

        // PaddleOCR check is async-friendly; we'll re-check on first use
        try {
            paddleAvailable = paddleOcrClient.isAvailable()
            if (paddleAvailable) {
                log.info("PaddleOCR service available - using as primary OCR engine")
            } else {
                log.info("PaddleOCR service not available - using Tika/Tesseract pipeline")
            }
        } catch (_: Exception) {
            log.info("PaddleOCR service not configured")
        }
    }

    data class OcrResult(
        val text: String,
        val engine: OcrEngine,
        val confidence: Double = -1.0,
        val pageCount: Int = 1
    )

    enum class OcrEngine { TIKA, TESSERACT, PADDLEOCR, TIKA_PLUS_TESSERACT }

    fun extractText(inputStream: InputStream, fileName: String): String {
        return extractWithDetails(inputStream, fileName, null).text
    }

    fun extractWithDetails(inputStream: InputStream, fileName: String, filePath: Path? = null): OcrResult {
        log.info("Extracting text from: {}", fileName)

        // Phase 1: Tika (texte natif PDF)
        val tikaText = textNormalizationService.normalize(extractWithTika(inputStream, fileName))
        val tikaWords = countSignificantWords(tikaText)
        log.info("Tika extracted {} chars ({} words) from {}", tikaText.length, tikaWords, fileName)

        // Only skip OCR if Tika found substantial text (>100 words = truly native PDF)
        if (tikaWords >= 100) {
            log.info("Tika extraction rich for {} ({}+ words), skipping OCR", fileName, tikaWords)
            return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = countPages(filePath))
        }

        // Phase 2: Always try PaddleOCR if available (even if Tika found some text)
        if (filePath != null) {
            val paddleResult = tryPaddleOcr(filePath)
            if (paddleResult != null) {
                // Keep the result with more content
                val paddleWords = countSignificantWords(paddleResult.text)
                if (paddleWords > tikaWords) {
                    log.info("PaddleOCR better than Tika ({} vs {} words) for {}", paddleWords, tikaWords, fileName)
                    return paddleResult
                }
                if (tikaWords > 0) {
                    log.info("Tika better than PaddleOCR ({} vs {} words) for {}", tikaWords, paddleWords, fileName)
                    return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = paddleResult.pageCount)
                }
                return paddleResult
            }
        }

        // Phase 3: Tesseract fallback
        if (tesseractAvailable && filePath != null) {
            val tessResult = tryTesseract(filePath, fileName, tikaText)
            if (tessResult != null) return tessResult
        }

        // Dernier recours
        return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = 1)
    }

    /**
     * Appelle le microservice PaddleOCR. Retourne null si indisponible.
     */
    private fun tryPaddleOcr(filePath: Path): OcrResult? {
        // Re-check availability (le service peut demarrer apres le backend)
        if (!paddleAvailable) {
            try {
                paddleAvailable = paddleOcrClient.isAvailable()
            } catch (_: Exception) { return null }
        }
        if (!paddleAvailable) return null

        return try {
            val result = paddleOcrClient.ocr(filePath)
            val normalized = textNormalizationService.normalize(result.text)

            if (normalized.isBlank()) {
                log.warn("PaddleOCR returned empty text, falling back")
                return null
            }

            log.info("PaddleOCR: {} chars, {} pages, {:.1f}% confidence",
                normalized.length, result.pageCount, result.confidence * 100)

            OcrResult(
                text = normalized,
                engine = OcrEngine.PADDLEOCR,
                confidence = result.confidence * 100,
                pageCount = result.pageCount
            )
        } catch (e: Exception) {
            log.warn("PaddleOCR call failed, falling back to Tesseract: {}", e.message)
            paddleAvailable = false // disable until next health check
            null
        }
    }

    /**
     * Tesseract OCR local avec multi-PSM et preprocessing.
     */
    private fun tryTesseract(filePath: Path, fileName: String, tikaText: String): OcrResult? {
        val isImage = isImageFile(fileName)
        val isPdf = isPdfFile(fileName)

        val tesseractResult = when {
            isPdf -> ocrPdfWithTesseract(filePath)
            isImage -> ocrImageWithTesseract(filePath)
            else -> null
        } ?: return null

        if (tesseractResult.text.isBlank()) return null

        // Comparer avec Tika si Tika avait un peu de texte
        if (tikaText.isNotBlank()) {
            val tikaWords = countSignificantWords(tikaText)
            val tessWords = countSignificantWords(tesseractResult.text)
            if (tessWords <= tikaWords) {
                return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = tesseractResult.pageCount)
            }
        }

        return tesseractResult
    }

    // --- Tika ---

    private fun extractWithTika(inputStream: InputStream, fileName: String): String {
        return try {
            val metadata = Metadata()
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
            tika.parseToString(inputStream, metadata)
        } catch (e: Exception) {
            log.warn("Tika extraction failed for {}: {}", fileName, e.message)
            ""
        }
    }

    // --- Tesseract ---

    private fun ocrPdfWithTesseract(filePath: Path): OcrResult? {
        return try {
            Loader.loadPDF(filePath.toFile()).use { document ->
                val renderer = PDFRenderer(document)
                val pageCount = document.pages.count
                val allText = StringBuilder()

                for (page in 0 until pageCount) {
                    val pageImage = renderer.renderImageWithDPI(page, dpi.toFloat(), ImageType.RGB)
                    val processedImage = preprocessingService.preprocess(pageImage)
                    val pageText = ocrWithBestPsm(processedImage)
                    allText.append(pageText).append("\n\n")
                }

                val normalized = textNormalizationService.normalize(allText.toString())
                OcrResult(text = normalized, engine = OcrEngine.TESSERACT, pageCount = pageCount)
            }
        } catch (e: Exception) {
            log.error("Tesseract PDF OCR failed: {}", e.message)
            null
        }
    }

    private fun ocrImageWithTesseract(filePath: Path): OcrResult? {
        return try {
            val image = ImageIO.read(filePath.toFile()) ?: return null
            val processedImage = preprocessingService.preprocess(image)
            val text = textNormalizationService.normalize(ocrWithBestPsm(processedImage))
            OcrResult(text = text, engine = OcrEngine.TESSERACT, pageCount = 1)
        } catch (e: Exception) {
            log.error("Tesseract image OCR failed: {}", e.message)
            null
        }
    }

    private fun ocrWithBestPsm(image: BufferedImage): String {
        data class PsmResult(val psm: Int, val text: String, val score: Int)
        val results = mutableListOf<PsmResult>()

        for (psm in listOf(3, 6)) {
            try {
                val tesseract = createTesseract(psm)
                val text = tesseract.doOCR(image).trim()
                results.add(PsmResult(psm, text, scoreOcrResult(text)))
            } catch (e: Exception) {
                log.debug("PSM {} failed: {}", psm, e.message)
            }
        }

        return results.maxByOrNull { it.score }?.text ?: ""
    }

    private fun scoreOcrResult(text: String): Int {
        if (text.isBlank()) return 0
        var score = text.split(Regex("\\s+")).count { it.length > 2 } * 2
        score += Regex("\\d{3,}").findAll(text).count() * 5
        val keywords = listOf("facture", "total", "tva", "montant", "ice", "ht", "ttc",
            "fournisseur", "client", "date", "rib", "banque", "paiement")
        for (kw in keywords) {
            if (text.contains(kw, ignoreCase = true)) score += 10
        }
        score -= text.lines().count { it.trim().length in 1..2 } * 3
        return score
    }

    private fun createTesseract(psm: Int = 3): Tesseract {
        return Tesseract().apply {
            setDatapath(resolvedTessDataPath)
            setLanguage(languages)
            setOcrEngineMode(oem)
            setPageSegMode(psm)
            setVariable("preserve_interword_spaces", "1")
            setVariable("tessedit_char_blacklist", "{}|~`")
            setVariable("classify_bln_numeric_mode", "1")
        }
    }

    // --- Utils ---

    private fun isTextSufficient(text: String): Boolean {
        if (text.isBlank()) return false
        return text.split(Regex("\\s+")).count { it.length > 1 } >= 20
    }

    private fun countSignificantWords(text: String): Int =
        text.split(Regex("\\s+")).count { it.length > 1 }

    private fun countPages(filePath: Path?): Int {
        if (filePath == null || !isPdfFile(filePath.toString())) return 1
        return try { Loader.loadPDF(filePath.toFile()).use { it.pages.count } } catch (_: Exception) { 1 }
    }

    private fun checkTesseractAvailability(): Boolean {
        val paths = listOf(tessDataPath, "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata", "/usr/share/tessdata",
            "/usr/local/share/tessdata", "/opt/homebrew/share/tessdata")
        for (p in paths) {
            if (Files.exists(Path.of(p))) { resolvedTessDataPath = p; return true }
        }
        return false
    }

    private fun isImageFile(fileName: String): Boolean =
        fileName.lowercase().substringAfterLast('.', "") in listOf("jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif", "webp")

    private fun isPdfFile(fileName: String): Boolean =
        fileName.lowercase().endsWith(".pdf")
}
