package com.ocrsage.service

import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.ImageType
import org.apache.tika.Tika
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

@Service
class OcrService(
    private val preprocessingService: ImagePreprocessingService,
    private val textNormalizationService: TextNormalizationService,
    @Value("\${ocr.tesseract.data-path:/usr/share/tesseract-ocr/5/tessdata}") private val tessDataPath: String,
    @Value("\${ocr.tesseract.languages:fra+ara}") private val languages: String,
    @Value("\${ocr.tesseract.dpi:300}") private val dpi: Int,
    @Value("\${ocr.tesseract.oem:1}") private val oem: Int,
    @Value("\${ocr.confidence-threshold:30}") private val confidenceThreshold: Int
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tika = Tika()

    private var tesseractAvailable: Boolean = false
    private var resolvedTessDataPath: String = tessDataPath

    init {
        tesseractAvailable = checkTesseractAvailability()
        if (tesseractAvailable) {
            log.info("Tesseract OCR available (dataPath={}, languages={}, OEM={})",
                resolvedTessDataPath, languages, oem)
        } else {
            log.warn("Tesseract OCR not available - falling back to Tika-only extraction")
        }
    }

    data class OcrResult(
        val text: String,
        val engine: OcrEngine,
        val confidence: Double = -1.0,
        val pageCount: Int = 1
    )

    enum class OcrEngine { TIKA, TESSERACT, TIKA_PLUS_TESSERACT }

    fun extractText(inputStream: InputStream, fileName: String): String {
        return extractWithDetails(inputStream, fileName, null).text
    }

    fun extractWithDetails(inputStream: InputStream, fileName: String, filePath: Path? = null): OcrResult {
        log.info("Extracting text from: {}", fileName)

        // Phase 1: Tika (texte natif PDF)
        val tikaText = extractWithTika(inputStream, fileName)
        val normalizedTika = textNormalizationService.normalize(tikaText)
        log.info("Tika extracted {} characters from {}", normalizedTika.length, fileName)

        if (isTextSufficient(normalizedTika)) {
            log.info("Tika extraction sufficient for {}, skipping Tesseract", fileName)
            return OcrResult(text = normalizedTika, engine = OcrEngine.TIKA, pageCount = countPages(filePath))
        }

        // Phase 2: Tesseract avec preprocessing + multi-PSM
        if (!tesseractAvailable) {
            log.info("Tesseract not available, using Tika-only result for {}", fileName)
            return OcrResult(text = normalizedTika, engine = OcrEngine.TIKA, pageCount = 1)
        }

        val isImage = isImageFile(fileName)
        val isPdf = isPdfFile(fileName)

        val tesseractResult = when {
            isPdf && filePath != null -> ocrPdfWithTesseract(filePath)
            isImage && filePath != null -> ocrImageWithTesseract(filePath)
            else -> {
                log.debug("File type not suitable for Tesseract OCR: {}", fileName)
                null
            }
        }

        if (tesseractResult == null || tesseractResult.text.isBlank()) {
            return OcrResult(text = normalizedTika, engine = OcrEngine.TIKA, pageCount = 1)
        }

        // Phase 3: Choisir le meilleur resultat
        if (normalizedTika.isBlank()) {
            return tesseractResult
        }

        // Comparer la richesse : compter les mots significatifs
        val tikaWords = countSignificantWords(normalizedTika)
        val tessWords = countSignificantWords(tesseractResult.text)

        return if (tessWords > tikaWords * 1.15) {
            log.info("Tesseract richer ({} vs {} words), using Tesseract", tessWords, tikaWords)
            tesseractResult
        } else {
            log.info("Tika sufficient ({} words), keeping Tika result", tikaWords)
            OcrResult(text = normalizedTika, engine = OcrEngine.TIKA, pageCount = tesseractResult.pageCount)
        }
    }

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

    /**
     * OCR PDF page par page avec strategie multi-PSM :
     * 1. PSM 3 (fully automatic page segmentation) - meilleur pour layouts complexes
     * 2. Si resultat pauvre, PSM 6 (single uniform block) en fallback
     */
    private fun ocrPdfWithTesseract(filePath: Path): OcrResult? {
        return try {
            Loader.loadPDF(filePath.toFile()).use { document ->
                val renderer = PDFRenderer(document)
                val pageCount = document.pages.count
                val allText = StringBuilder()

                log.info("OCR PDF with Tesseract: {} pages at {} DPI (multi-PSM)", pageCount, dpi)

                for (page in 0 until pageCount) {
                    val pageImage = renderer.renderImageWithDPI(page, dpi.toFloat(), ImageType.RGB)
                    val processedImage = preprocessingService.preprocess(pageImage)

                    // Strategie multi-PSM : essayer PSM 3 d'abord, PSM 6 en fallback
                    val pageText = ocrWithBestPsm(processedImage)
                    allText.append(pageText).append("\n\n")

                    log.debug("Page {}/{}: {} characters", page + 1, pageCount, pageText.length)
                }

                val normalized = textNormalizationService.normalize(allText.toString())
                OcrResult(
                    text = normalized,
                    engine = OcrEngine.TESSERACT,
                    confidence = -1.0,
                    pageCount = pageCount
                )
            }
        } catch (e: Exception) {
            log.error("Tesseract PDF OCR failed for {}: {}", filePath, e.message)
            null
        }
    }

    private fun ocrImageWithTesseract(filePath: Path): OcrResult? {
        return try {
            val image = ImageIO.read(filePath.toFile()) ?: return null
            val processedImage = preprocessingService.preprocess(image)
            val text = ocrWithBestPsm(processedImage)
            val normalized = textNormalizationService.normalize(text)
            OcrResult(text = normalized, engine = OcrEngine.TESSERACT, pageCount = 1)
        } catch (e: Exception) {
            log.error("Tesseract image OCR failed for {}: {}", filePath, e.message)
            null
        }
    }

    /**
     * Essaie plusieurs PSM et garde le meilleur resultat.
     * PSM 3 = fully automatic (bon pour multi-colonnes)
     * PSM 4 = single column (bon pour factures simples)
     * PSM 6 = single block (fallback)
     */
    private fun ocrWithBestPsm(image: BufferedImage): String {
        data class PsmResult(val psm: Int, val text: String, val score: Int)

        val results = mutableListOf<PsmResult>()

        for (psm in listOf(3, 6)) {
            try {
                val tesseract = createTesseract(psm)
                val text = tesseract.doOCR(image).trim()
                val score = scoreOcrResult(text)
                results.add(PsmResult(psm, text, score))
            } catch (e: Exception) {
                log.debug("PSM {} failed: {}", psm, e.message)
            }
        }

        if (results.isEmpty()) return ""

        val best = results.maxByOrNull { it.score } ?: return ""
        if (results.size > 1) {
            log.debug("Multi-PSM: PSM {} won (score {}) vs {}",
                best.psm, best.score, results.filter { it.psm != best.psm }.map { "PSM ${it.psm}=${it.score}" })
        }
        return best.text
    }

    /**
     * Score la qualite d'un resultat OCR pour comparer entre PSMs.
     * Favorise : mots longs, chiffres, mots-cles facture.
     */
    private fun scoreOcrResult(text: String): Int {
        if (text.isBlank()) return 0

        var score = 0
        val words = text.split(Regex("\\s+"))

        // Mots significatifs (longueur > 2)
        score += words.count { it.length > 2 } * 2

        // Bonus pour chiffres (montants, IDs)
        score += Regex("\\d{3,}").findAll(text).count() * 5

        // Bonus pour mots-cles facture
        val keywords = listOf("facture", "total", "tva", "montant", "ice", "ht", "ttc",
            "fournisseur", "client", "date", "rib", "banque", "paiement", "quantit",
            "prix", "unitaire", "designation", "remise", "net")
        for (kw in keywords) {
            if (text.contains(kw, ignoreCase = true)) score += 10
        }

        // Penalite pour lignes tres courtes (bruit OCR)
        val shortLines = text.lines().count { it.trim().length in 1..2 }
        score -= shortLines * 3

        return score
    }

    private fun createTesseract(psm: Int = 3): Tesseract {
        return Tesseract().apply {
            setDatapath(resolvedTessDataPath)
            setLanguage(languages)
            setOcrEngineMode(oem)
            setPageSegMode(psm)
            // Optimisations pour factures
            setVariable("preserve_interword_spaces", "1")
            setVariable("tessedit_char_blacklist", "{}|~`")
            // Ameliorer la reconnaissance des chiffres
            setVariable("classify_bln_numeric_mode", "1")
            // Forcer UTF-8
            setVariable("tessedit_char_whitelist", "")
        }
    }

    private fun isTextSufficient(text: String): Boolean {
        if (text.isBlank()) return false
        val words = text.split(Regex("\\s+")).filter { it.length > 1 }
        return words.size >= 20
    }

    private fun countSignificantWords(text: String): Int {
        return text.split(Regex("\\s+")).count { it.length > 1 }
    }

    private fun countPages(filePath: Path?): Int {
        if (filePath == null || !isPdfFile(filePath.toString())) return 1
        return try {
            Loader.loadPDF(filePath.toFile()).use { it.pages.count }
        } catch (_: Exception) { 1 }
    }

    private fun checkTesseractAvailability(): Boolean {
        val paths = listOf(
            tessDataPath,
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tessdata",
            "/usr/local/share/tessdata",
            "/opt/homebrew/share/tessdata"
        )
        for (p in paths) {
            if (Files.exists(Path.of(p))) {
                resolvedTessDataPath = p
                return true
            }
        }
        return false
    }

    private fun isImageFile(fileName: String): Boolean {
        val ext = fileName.lowercase().substringAfterLast('.', "")
        return ext in listOf("jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif", "webp")
    }

    private fun isPdfFile(fileName: String): Boolean {
        return fileName.lowercase().endsWith(".pdf")
    }
}
