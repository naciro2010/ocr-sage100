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

/**
 * Service OCR avec pipeline multi-engine:
 * 1. Tika: extraction texte natif (PDF numeriques)
 * 2. Tesseract via Tess4J: OCR avec preprocessing (PDF scannes, images)
 *
 * Si Tika extrait suffisamment de texte -> utilise directement le resultat.
 * Sinon -> preprocessing image + Tesseract OCR page par page.
 */
@Service
class OcrService(
    private val preprocessingService: ImagePreprocessingService,
    @Value("\${ocr.tesseract.data-path:/usr/share/tesseract-ocr/5/tessdata}") private val tessDataPath: String,
    @Value("\${ocr.tesseract.languages:fra+ara}") private val languages: String,
    @Value("\${ocr.tesseract.dpi:300}") private val dpi: Int,
    @Value("\${ocr.tesseract.oem:1}") private val oem: Int,
    @Value("\${ocr.tesseract.psm:6}") private val psm: Int,
    @Value("\${ocr.confidence-threshold:30}") private val confidenceThreshold: Int
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tika = Tika()

    private var tesseractAvailable: Boolean = false

    init {
        tesseractAvailable = checkTesseractAvailability()
        if (tesseractAvailable) {
            log.info("Tesseract OCR available (dataPath={}, languages={}, OEM={}, PSM={})",
                tessDataPath, languages, oem, psm)
        } else {
            log.warn("Tesseract OCR not available - falling back to Tika-only extraction. " +
                "Install tesseract-ocr and language packs for improved scanned document support.")
        }
    }

    /**
     * Resultat OCR avec metadonnees de qualite.
     */
    data class OcrResult(
        val text: String,
        val engine: OcrEngine,
        val confidence: Double = -1.0,
        val pageCount: Int = 1
    )

    enum class OcrEngine { TIKA, TESSERACT, TIKA_PLUS_TESSERACT }

    /**
     * Extrait le texte d'un fichier avec cascade intelligente:
     * 1. Essaie Tika d'abord (rapide pour PDF natifs)
     * 2. Si resultat insuffisant et Tesseract disponible -> OCR avec preprocessing
     * 3. Fusionne les resultats si les deux ont du contenu utile
     */
    fun extractText(inputStream: InputStream, fileName: String): String {
        return extractWithDetails(inputStream, fileName, null).text
    }

    /**
     * Extraction avec details complets (engine utilisee, confiance, etc.).
     * Accepte optionnellement le chemin du fichier pour acces direct PDF.
     */
    fun extractWithDetails(inputStream: InputStream, fileName: String, filePath: Path? = null): OcrResult {
        log.info("Extracting text from: {}", fileName)

        // Phase 1: Extraction Tika (texte natif)
        val tikaText = extractWithTika(inputStream, fileName)
        log.info("Tika extracted {} characters from {}", tikaText.length, fileName)

        // Si Tika a extrait suffisamment de texte significatif, pas besoin d'OCR
        if (isTextSufficient(tikaText)) {
            log.info("Tika extraction sufficient for {}, skipping Tesseract", fileName)
            return OcrResult(text = tikaText.trim(), engine = OcrEngine.TIKA, pageCount = 1)
        }

        // Phase 2: Tesseract OCR avec preprocessing
        if (!tesseractAvailable) {
            log.info("Tesseract not available, using Tika-only result for {}", fileName)
            return OcrResult(text = tikaText.trim(), engine = OcrEngine.TIKA, pageCount = 1)
        }

        val isImage = isImageFile(fileName)
        val isPdf = isPdfFile(fileName)

        val tesseractResult = when {
            isPdf && filePath != null -> ocrPdfWithTesseract(filePath)
            isPdf && filePath == null -> {
                log.warn("PDF file path not provided for Tesseract, using Tika-only result")
                return OcrResult(text = tikaText.trim(), engine = OcrEngine.TIKA, pageCount = 1)
            }
            isImage -> {
                val resolvedPath = filePath
                if (resolvedPath != null) {
                    ocrImageWithTesseract(resolvedPath)
                } else {
                    log.warn("Image file path not provided for Tesseract")
                    return OcrResult(text = tikaText.trim(), engine = OcrEngine.TIKA, pageCount = 1)
                }
            }
            else -> {
                log.debug("File type not suitable for Tesseract OCR: {}", fileName)
                return OcrResult(text = tikaText.trim(), engine = OcrEngine.TIKA, pageCount = 1)
            }
        }

        // Phase 3: Choisir ou fusionner les resultats
        if (tesseractResult == null || tesseractResult.text.isBlank()) {
            return OcrResult(text = tikaText.trim(), engine = OcrEngine.TIKA, pageCount = 1)
        }

        // Si Tika n'a rien donne, utiliser Tesseract directement
        if (tikaText.isBlank()) {
            return tesseractResult
        }

        // Les deux ont du contenu: prendre le plus riche
        return if (tesseractResult.text.length > tikaText.length * 1.2) {
            log.info("Tesseract produced richer text ({} vs {} chars), using Tesseract result",
                tesseractResult.text.length, tikaText.length)
            tesseractResult
        } else {
            log.info("Tika text is sufficient ({} chars), using Tika result", tikaText.length)
            OcrResult(text = tikaText.trim(), engine = OcrEngine.TIKA, pageCount = tesseractResult.pageCount)
        }
    }

    /**
     * Extraction texte via Apache Tika (PDF natifs, documents bureautiques).
     */
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
     * OCR d'un PDF page par page avec Tesseract.
     * Chaque page est rendue en image haute resolution, preprocessee, puis OCR.
     */
    private fun ocrPdfWithTesseract(filePath: Path): OcrResult? {
        return try {
            Loader.loadPDF(filePath.toFile()).use { document ->
                val renderer = PDFRenderer(document)
                val pageCount = document.pages.count
                val allText = StringBuilder()
                var totalConfidence = 0.0

                log.info("OCR PDF with Tesseract: {} pages at {} DPI", pageCount, dpi)

                for (page in 0 until pageCount) {
                    // Rendre la page en image haute resolution
                    val pageImage = renderer.renderImageWithDPI(page, dpi.toFloat(), ImageType.RGB)

                    // Preprocessing
                    val processedImage = preprocessingService.preprocess(pageImage)

                    // OCR Tesseract
                    val tesseract = createTesseract()
                    val pageText = tesseract.doOCR(processedImage)
                    allText.append(pageText).append("\n\n")

                    log.debug("Page {}/{}: extracted {} characters", page + 1, pageCount, pageText.length)
                }

                val text = allText.toString().trim()
                val avgConfidence = if (pageCount > 0) totalConfidence / pageCount else 0.0

                OcrResult(
                    text = text,
                    engine = OcrEngine.TESSERACT,
                    confidence = avgConfidence,
                    pageCount = pageCount
                )
            }
        } catch (e: Exception) {
            log.error("Tesseract PDF OCR failed for {}: {}", filePath, e.message)
            null
        }
    }

    /**
     * OCR d'une image unique avec Tesseract.
     */
    private fun ocrImageWithTesseract(filePath: Path): OcrResult? {
        return try {
            val image = ImageIO.read(filePath.toFile())
            if (image == null) {
                log.warn("Could not read image file: {}", filePath)
                return null
            }

            // Preprocessing
            val processedImage = preprocessingService.preprocess(image)

            // OCR
            val tesseract = createTesseract()
            val text = tesseract.doOCR(processedImage)

            OcrResult(
                text = text.trim(),
                engine = OcrEngine.TESSERACT,
                pageCount = 1
            )
        } catch (e: Exception) {
            log.error("Tesseract image OCR failed for {}: {}", filePath, e.message)
            null
        }
    }

    /**
     * Cree une instance Tesseract configuree.
     */
    private fun createTesseract(): Tesseract {
        return Tesseract().apply {
            setDatapath(tessDataPath)
            setLanguage(languages)
            setOcrEngineMode(oem)    // 1 = LSTM only
            setPageSegMode(psm)       // 6 = single uniform block
            // Optimisations pour factures
            setVariable("preserve_interword_spaces", "1")
            setVariable("tessedit_char_blacklist", "{}|~`")
        }
    }

    /**
     * Verifie si le texte extrait par Tika est suffisant.
     * Un PDF numerique produira du texte riche; un scan produira peu ou pas de texte.
     */
    private fun isTextSufficient(text: String): Boolean {
        if (text.isBlank()) return false
        // Compter les mots significatifs (pas juste des espaces/symboles)
        val words = text.split(Regex("\\s+")).filter { it.length > 1 }
        // Un document de facture devrait avoir au moins 20 mots significatifs
        return words.size >= 20
    }

    /**
     * Verifie si Tesseract est installe et fonctionnel.
     */
    private fun checkTesseractAvailability(): Boolean {
        return try {
            val dataPath = Path.of(tessDataPath)
            if (!Files.exists(dataPath)) {
                log.debug("Tessdata directory not found: {}", tessDataPath)
                // Essayer des chemins alternatifs courants
                val alternatives = listOf(
                    "/usr/share/tesseract-ocr/5/tessdata",
                    "/usr/share/tesseract-ocr/4.00/tessdata",
                    "/usr/share/tessdata",
                    "/usr/local/share/tessdata",
                    "/opt/homebrew/share/tessdata"
                )
                for (alt in alternatives) {
                    if (Files.exists(Path.of(alt))) {
                        log.info("Found tessdata at alternative path: {}", alt)
                        return true
                    }
                }
                false
            } else {
                true
            }
        } catch (e: Exception) {
            log.debug("Error checking Tesseract availability: {}", e.message)
            false
        }
    }

    private fun isImageFile(fileName: String): Boolean {
        val ext = fileName.lowercase().substringAfterLast('.', "")
        return ext in listOf("jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif", "webp")
    }

    private fun isPdfFile(fileName: String): Boolean {
        return fileName.lowercase().endsWith(".pdf")
    }
}
