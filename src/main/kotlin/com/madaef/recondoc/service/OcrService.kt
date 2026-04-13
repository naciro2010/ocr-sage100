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
 *
 * Strategie : combiner les resultats des moteurs pour maximiser le contenu extrait.
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
    @Value("\${ocr.confidence-threshold:25}") private val confidenceThreshold: Int
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

    enum class OcrEngine { TIKA, TESSERACT, PADDLEOCR, TIKA_PLUS_TESSERACT, COMBINED }

    fun extractText(inputStream: InputStream, fileName: String): String {
        return extractWithDetails(inputStream, fileName, null).text
    }

    fun extractWithDetails(inputStream: InputStream, fileName: String, filePath: Path? = null): OcrResult {
        log.info("Extracting text from: {}", fileName)

        // Phase 1: Tika (texte natif PDF)
        val tikaText = textNormalizationService.normalize(extractWithTika(inputStream, fileName))
        val tikaWords = countSignificantWords(tikaText)
        log.info("Tika extracted {} chars ({} words) from {}", tikaText.length, tikaWords, fileName)

        // Si Tika a trouve beaucoup de texte natif, c'est un PDF numerique
        if (tikaWords >= 200) {
            log.info("Tika extraction rich for {} ({}+ words), skipping OCR", fileName, tikaWords)
            return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = countPages(filePath))
        }

        // Phase 2: PaddleOCR (toujours tenter si disponible)
        var paddleResult: OcrResult? = null
        if (filePath != null) {
            paddleResult = tryPaddleOcr(filePath)
            if (paddleResult != null) {
                val paddleWords = countSignificantWords(paddleResult.text)
                log.info("PaddleOCR: {} words, {:.1f}% confidence for {}", paddleWords, paddleResult.confidence, fileName)

                // Combiner Tika + Paddle si les deux ont du contenu
                if (tikaWords >= 20 && paddleWords >= 20) {
                    val combined = mergeTexts(tikaText, paddleResult.text)
                    val combinedWords = countSignificantWords(combined)
                    if (combinedWords > paddleWords && combinedWords > tikaWords) {
                        log.info("Combined Tika+Paddle: {} words (was Tika={}, Paddle={})", combinedWords, tikaWords, paddleWords)
                        return OcrResult(text = combined, engine = OcrEngine.COMBINED, confidence = paddleResult.confidence, pageCount = paddleResult.pageCount)
                    }
                }

                // Sinon garder le meilleur
                if (paddleWords > tikaWords) {
                    return paddleResult
                }
                if (tikaWords >= 30) {
                    return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = paddleResult.pageCount)
                }
                return paddleResult
            }
        }

        // Phase 3: Tesseract fallback (si PaddleOCR indisponible ou echoue)
        if (tesseractAvailable && filePath != null) {
            val tessResult = tryTesseract(filePath, fileName, tikaText)
            if (tessResult != null) {
                val tessWords = countSignificantWords(tessResult.text)
                // Combiner Tika + Tesseract
                if (tikaWords >= 10 && tessWords >= 10) {
                    val combined = mergeTexts(tikaText, tessResult.text)
                    val combinedWords = countSignificantWords(combined)
                    if (combinedWords > tessWords && combinedWords > tikaWords) {
                        return OcrResult(text = combined, engine = OcrEngine.TIKA_PLUS_TESSERACT, pageCount = tessResult.pageCount)
                    }
                }
                return tessResult
            }
        }

        return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = 1)
    }

    /**
     * PaddleOCR avec fallback arabe si le francais est pauvre.
     */
    private fun tryPaddleOcr(filePath: Path): OcrResult? {
        if (!paddleAvailable) {
            try { paddleAvailable = paddleOcrClient.isAvailable() } catch (_: Exception) { return null }
        }
        if (!paddleAvailable) return null

        return try {
            val result = paddleOcrClient.ocr(filePath, "fr")
            val normalized = textNormalizationService.normalize(result.text)
            val frWords = countSignificantWords(normalized)

            // Si le francais est pauvre, tenter en arabe
            if (frWords < 15) {
                try {
                    val arResult = paddleOcrClient.ocr(filePath, "ar")
                    val arNormalized = textNormalizationService.normalize(arResult.text)
                    val arWords = countSignificantWords(arNormalized)
                    if (arWords > frWords) {
                        log.info("PaddleOCR Arabic better ({} vs {} words)", arWords, frWords)
                        // Combiner fr + ar
                        val combined = if (frWords > 5) mergeTexts(normalized, arNormalized) else arNormalized
                        return OcrResult(text = combined, engine = OcrEngine.PADDLEOCR, confidence = arResult.confidence * 100, pageCount = arResult.pageCount)
                    }
                } catch (e: Exception) {
                    log.debug("PaddleOCR Arabic fallback failed: {}", e.message)
                }
            }

            if (normalized.isBlank()) {
                log.warn("PaddleOCR returned empty text, falling back")
                return null
            }

            OcrResult(text = normalized, engine = OcrEngine.PADDLEOCR, confidence = result.confidence * 100, pageCount = result.pageCount)
        } catch (e: Exception) {
            log.warn("PaddleOCR call failed: {}", e.message)
            paddleAvailable = false
            null
        }
    }

    private fun tryTesseract(filePath: Path, fileName: String, tikaText: String): OcrResult? {
        val isImage = isImageFile(fileName)
        val isPdf = isPdfFile(fileName)

        val tesseractResult = when {
            isPdf -> ocrPdfWithTesseract(filePath)
            isImage -> ocrImageWithTesseract(filePath)
            else -> null
        } ?: return null

        if (tesseractResult.text.isBlank()) return null

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
                    var pageText = ocrWithBestPsm(processedImage)

                    // DPI adaptatif : si le score est faible, retenter a DPI superieur
                    if (scoreOcrResult(pageText) < 40 && dpi < 400) {
                        log.debug("Low OCR score on page {}, retrying at 400 DPI", page)
                        val highDpiImage = renderer.renderImageWithDPI(page, 400f, ImageType.RGB)
                        val highDpiProcessed = preprocessingService.preprocess(highDpiImage)
                        val highDpiText = ocrWithBestPsm(highDpiProcessed)
                        if (scoreOcrResult(highDpiText) > scoreOcrResult(pageText)) {
                            pageText = highDpiText
                        }
                    }

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

    /**
     * Essaie les PSMs en ordre, short-circuit si le score est bon.
     */
    private fun ocrWithBestPsm(image: BufferedImage): String {
        data class PsmResult(val psm: Int, val text: String, val score: Int)
        val results = mutableListOf<PsmResult>()

        for (psm in listOf(3, 6, 4, 11)) {
            try {
                val tesseract = createTesseract(psm)
                val text = tesseract.doOCR(image).trim()
                val score = scoreOcrResult(text)
                if (score >= confidenceThreshold) {
                    results.add(PsmResult(psm, text, score))
                    // Short-circuit : si le score est tres bon, pas besoin de tester les autres PSMs
                    if (score >= 80) {
                        log.debug("PSM {} excellent score ({}), short-circuiting", psm, score)
                        break
                    }
                }
            } catch (e: Exception) {
                log.debug("PSM {} failed: {}", psm, e.message)
            }
        }

        return results.maxByOrNull { it.score }?.text ?: ""
    }

    /**
     * Scoring elargi : plus de keywords financiers marocains + structure detection.
     */
    private fun scoreOcrResult(text: String): Int {
        if (text.isBlank()) return 0
        val words = text.split(Regex("\\s+"))
        var score = words.count { it.length > 2 } * 2

        // Bonus pour les nombres (montants, references)
        score += Regex("\\d{3,}").findAll(text).count() * 5
        // Bonus pour les montants avec decimales
        score += Regex("\\d+[.,]\\d{2}").findAll(text).count() * 8

        // Keywords elargis — financiers marocains
        val keywords = listOf(
            "facture", "total", "tva", "montant", "ice", "ht", "ttc",
            "fournisseur", "client", "date", "rib", "banque", "paiement",
            "commande", "contrat", "avenant", "prestation", "reception",
            "attestation", "fiscale", "identifiant", "patente", "cnss",
            "dirham", "dh", "mad", "net", "brut", "retenue",
            "bon", "ordre", "controle", "autocontrole", "checklist",
            "signature", "visa", "objet", "reference", "numero"
        )
        for (kw in keywords) {
            if (text.contains(kw, ignoreCase = true)) score += 8
        }

        // Bonus pour structure de document (lignes avec ":" qui indiquent des champs)
        score += text.lines().count { it.contains(":") && it.length > 10 } * 3

        // Penalite pour les lignes tres courtes (bruit)
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

    // --- Merge ---

    /**
     * Fusionne deux textes OCR pour maximiser le contenu.
     * Garde les lignes uniques des deux sources.
     */
    private fun mergeTexts(text1: String, text2: String): String {
        val lines1 = text1.lines().map { it.trim() }.filter { it.length > 3 }
        val lines2 = text2.lines().map { it.trim() }.filter { it.length > 3 }

        // Prendre text1 comme base, ajouter les lignes de text2 qui ne sont pas deja presentes
        val normalized1 = lines1.map { it.lowercase().replace(Regex("\\s+"), " ") }.toSet()
        val extraLines = lines2.filter { line ->
            val norm = line.lowercase().replace(Regex("\\s+"), " ")
            normalized1.none { existing -> existing.contains(norm) || norm.contains(existing) }
        }

        return if (extraLines.isEmpty()) {
            text1
        } else {
            text1 + "\n\n--- Complement ---\n" + extraLines.joinToString("\n")
        }
    }

    // --- Utils ---

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
