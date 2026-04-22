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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Pipeline OCR a 3 moteurs avec cascade intelligente :
 * 1. Tika       : extraction texte natif (PDF numeriques) - instantane
 * 1b.Markdown   : PDF numeriques avec tableaux -> Markdown structure (local)
 * 2. Mistral OCR: API cloud, rend du Markdown avec tableaux preserves
 * 3. Tesseract  : fallback local si Mistral indisponible ou non configure
 *
 * Strategie : combiner les resultats des moteurs pour maximiser le contenu extrait.
 */
@Service
class OcrService(
    private val preprocessingService: ImagePreprocessingService,
    private val textNormalizationService: TextNormalizationService,
    private val mistralOcrClient: MistralOcrClient,
    private val pdfMarkdownExtractor: PdfMarkdownExtractor,
    private val ocrSelectionService: OcrSelectionService,
    @Value("\${ocr.tesseract.data-path:/usr/share/tessdata}") private val tessDataPath: String,
    @Value("\${ocr.tesseract.languages:fra+ara}") private val languages: String,
    @Value("\${ocr.tesseract.dpi:300}") private val dpi: Int,
    @Value("\${ocr.tesseract.oem:1}") private val oem: Int,
    @Value("\${ocr.confidence-threshold:25}") private val confidenceThreshold: Int,
    @Value("\${ocr.parallel.enabled:true}") private val parallelEnabled: Boolean,
    @Value("\${ocr.parallel.mistral-timeout-s:90}") private val mistralTimeoutSeconds: Long,
    @Value("\${ocr.parallel.markdown-timeout-s:15}") private val markdownTimeoutSeconds: Long
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

    // Pool dedie pour les appels OCR paralleles. 4 threads = Tika (1) +
    // Mistral (1) + PdfMarkdown (1) + marge. Pas de competition avec le
    // pool HTTP general.
    private val ocrThreadCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val ocrExecutor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "ocr-parallel-${ocrThreadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    // Shutdown propre : libere les threads du pool OCR quand Spring detruit
    // le bean. Sans ca, chaque rechargement de contexte Spring (typiquement
    // les tests d'integration multi-classe) laisse 4 threads orphelins, ce
    // qui peut ralentir le teardown ou empecher certaines verifications de
    // resources de terminer proprement.
    @jakarta.annotation.PreDestroy
    fun shutdownOcrExecutor() {
        try {
            ocrExecutor.shutdown()
            if (!ocrExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ocrExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            log.warn("OCR executor shutdown interrupted: {}", e.message)
            Thread.currentThread().interrupt()
        }
    }

    init {
        tesseractAvailable = checkTesseractAvailability()
        if (tesseractAvailable) {
            log.info("Tesseract OCR available (dataPath={}, languages={})", resolvedTessDataPath, languages)
        }

        if (mistralOcrClient.isAvailable()) {
            log.info("Mistral OCR configured - used as primary OCR engine for scans")
        } else {
            log.info("Mistral OCR not configured - falling back to Tika + Tesseract only")
        }

        if (parallelEnabled) {
            log.info("OCR parallel mode ENABLED (Tika || Mistral || PdfMarkdown, then pick best by score)")
        } else {
            log.info("OCR parallel mode DISABLED (legacy cascade)")
        }
    }

    data class OcrResult(
        val text: String,
        val engine: OcrEngine,
        val confidence: Double = -1.0,
        val pageCount: Int = 1
    )

    enum class OcrEngine { TIKA, TESSERACT, MISTRAL_OCR, TIKA_PLUS_TESSERACT, COMBINED, PDFBOX_MARKDOWN }

    fun extractText(inputStream: InputStream, fileName: String): String {
        return extractWithDetails(inputStream, fileName, null).text
    }

    fun extractWithDetails(inputStream: InputStream, fileName: String, filePath: Path? = null): OcrResult {
        log.info("Extracting text from: {}", fileName)

        // Tika est toujours execute en premier (synchrone, local, < 1s). Il
        // consomme l'InputStream donc doit tourner sequentiellement. Mistral
        // et PdfMarkdown operent sur le Path et peuvent donc tourner en
        // parallele a Tika (et entre eux) si le mode parallele est active.
        val tikaText = textNormalizationService.normalize(extractWithTika(inputStream, fileName))
        val tikaWords = countSignificantWords(tikaText)
        log.info("Tika extracted {} chars ({} words) from {}", tikaText.length, tikaWords, fileName)

        if (parallelEnabled) {
            return extractParallel(tikaText, tikaWords, fileName, filePath)
        }
        return extractLegacyCascade(tikaText, tikaWords, fileName, filePath)
    }

    /**
     * Nouveau mode parallele : Tika (deja fait) || Mistral || PdfMarkdown,
     * puis scoring structure pour choisir le gagnant. Tesseract reste un
     * filet de secours quand les autres moteurs ne produisent rien.
     *
     * Avantages :
     *  - Factures numeriques avec tableaux : Mistral rend du Markdown
     *    structure la ou Tika produit un aplatissement par espaces.
     *  - Pas de court-circuit "Tika >= 200 mots": un texte long mais sans
     *    tables structurees est battu par un Markdown Mistral avec
     *    colonnes HT/TVA/TTC.
     *  - Tesseract n'est declenche que si aucune autre source n'a donne
     *    de texte exploitable (evite les doublons de bruit).
     */
    private fun extractParallel(
        tikaText: String,
        tikaWords: Int,
        fileName: String,
        filePath: Path?
    ): OcrResult {
        val tikaPageCount = countPages(filePath)
        val tikaCandidate = OcrSelectionService.Candidate(
            text = tikaText,
            engine = OcrEngine.TIKA,
            pageCount = tikaPageCount,
            confidence = computeTikaConfidence(tikaWords)
        )

        val futures = mutableListOf<CompletableFuture<OcrSelectionService.Candidate?>>()
        var mistralLaunched = false

        if (filePath != null && isPdfFile(fileName)) {
            futures += CompletableFuture.supplyAsync({ markdownCandidate(filePath) }, ocrExecutor)
        }
        if (filePath != null && mistralOcrClient.isAvailable()) {
            mistralLaunched = true
            futures += CompletableFuture.supplyAsync({ mistralCandidate(filePath) }, ocrExecutor)
        }

        // Attente bornee par les timeouts configures. Un timeout local
        // annule le future concerne sans bloquer les autres.
        val parallelResults = futures.mapNotNull { future ->
            try {
                future.get(
                    (if (mistralLaunched) mistralTimeoutSeconds else markdownTimeoutSeconds),
                    TimeUnit.SECONDS
                )
            } catch (e: Exception) {
                log.warn("OCR parallel task failed/timed out for {}: {}", fileName, e.message)
                future.cancel(true)
                null
            }
        }

        val candidates = mutableListOf(tikaCandidate)
        candidates += parallelResults

        // Tesseract en dernier recours : declenche uniquement si aucune source
        // n'a rien donne d'exploitable (texte tres pauvre partout). Evite la
        // double facturation CPU sur Tesseract quand Mistral a deja tout rendu.
        if (filePath != null && tesseractAvailable) {
            val bestSoFar = candidates.maxOfOrNull { countSignificantWords(it.text) } ?: 0
            if (bestSoFar < 30) {
                val tess = tesseractCandidate(filePath, fileName)
                if (tess != null) candidates += tess
            }
        }

        val winner = ocrSelectionService.pickBest(candidates) ?: tikaCandidate
        return OcrResult(
            text = winner.text,
            engine = winner.engine,
            confidence = winner.confidence,
            pageCount = winner.pageCount.takeIf { it > 0 } ?: 1
        )
    }

    /**
     * Ancien comportement en cascade, conserve pour rollback via le flag
     * `ocr.parallel.enabled=false` en cas d'incident.
     */
    private fun extractLegacyCascade(
        tikaText: String,
        tikaWords: Int,
        fileName: String,
        filePath: Path?
    ): OcrResult {
        if (tikaWords >= 200) {
            if (filePath != null && isPdfFile(fileName)) {
                val md = pdfMarkdownExtractor.extract(filePath)
                if (md != null && md.tableCount > 0) {
                    log.info("Upgraded to Markdown for {}: {} tables on {} pages", fileName, md.tableCount, md.pageCount)
                    return OcrResult(text = md.markdown, engine = OcrEngine.PDFBOX_MARKDOWN, pageCount = md.pageCount)
                }
            }
            log.info("Tika extraction rich for {} ({}+ words), skipping OCR", fileName, tikaWords)
            return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = countPages(filePath))
        }

        if (filePath != null && mistralOcrClient.isAvailable()) {
            val mistralResult = tryMistralOcr(filePath)
            if (mistralResult != null) {
                val mistralWords = countSignificantWords(mistralResult.text)
                log.info("Mistral OCR: {} words for {}", mistralWords, fileName)
                if (mistralWords > tikaWords) return mistralResult
                if (tikaWords >= 30) return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = mistralResult.pageCount)
                return mistralResult
            }
        }

        if (tesseractAvailable && filePath != null) {
            val tessResult = tryTesseract(filePath, fileName, tikaText)
            if (tessResult != null) return tessResult
        }

        return OcrResult(text = tikaText, engine = OcrEngine.TIKA, pageCount = 1)
    }

    private fun markdownCandidate(filePath: Path): OcrSelectionService.Candidate? {
        return try {
            val md = pdfMarkdownExtractor.extract(filePath) ?: return null
            if (md.markdown.isBlank()) return null
            // Confidence elevee si des tableaux ont ete detectes (valeur ajoutee
            // reelle vs Tika), moderee sinon (rendu parfois identique a Tika).
            val conf = if (md.tableCount > 0) 0.95 else 0.70
            OcrSelectionService.Candidate(
                text = md.markdown,
                engine = OcrEngine.PDFBOX_MARKDOWN,
                pageCount = md.pageCount,
                confidence = conf
            )
        } catch (e: Exception) {
            log.warn("PdfMarkdownExtractor failed: {}", e.message)
            null
        }
    }

    private fun mistralCandidate(filePath: Path): OcrSelectionService.Candidate? {
        return try {
            val r = mistralOcrClient.ocr(filePath)
            if (r.markdown.isBlank()) return null
            // Mistral OCR ne renvoie pas de score natif. On se base sur la
            // reussite (pages_processed > 0) + presence de tables Markdown
            // dans la sortie comme proxy de qualite.
            val hasTables = r.markdown.lines().any { line -> line.count { it == '|' } >= 3 }
            val conf = if (hasTables) 0.95 else 0.85
            OcrSelectionService.Candidate(
                text = r.markdown,
                engine = OcrEngine.MISTRAL_OCR,
                pageCount = r.pageCount,
                confidence = conf
            )
        } catch (e: Exception) {
            log.warn("Mistral OCR failed: {}", e.message)
            null
        }
    }

    private fun tesseractCandidate(filePath: Path, fileName: String): OcrSelectionService.Candidate? {
        val tessResult = tryTesseract(filePath, fileName, "") ?: return null
        if (tessResult.text.isBlank()) return null
        // scoreOcrResult est un heuristique deja calibre pour Tesseract,
        // on le normalise pour produire une confidence approchee.
        val rawScore = scoreOcrResult(tessResult.text)
        val conf = (rawScore.toDouble() / 200.0).coerceIn(0.20, 0.80)
        return OcrSelectionService.Candidate(
            text = tessResult.text,
            engine = tessResult.engine,
            pageCount = tessResult.pageCount,
            confidence = conf
        )
    }

    /**
     * Confidence Tika basee sur la densite de texte extrait. Un PDF natif
     * bien rempli (>= 300 mots) est note 1.0. Un texte tres court (scan
     * ou PDF image) tombe vers 0 — auquel cas Mistral ou Tesseract
     * prendront la main via le scoring.
     */
    private fun computeTikaConfidence(wordCount: Int): Double {
        if (wordCount <= 0) return 0.05
        return (wordCount.toDouble() / 300.0).coerceIn(0.05, 1.0)
    }

    /**
     * Appel Mistral OCR. Le service gere nativement FR + AR et rend du Markdown
     * avec tableaux preserves, donc pas besoin de double passe par langue.
     */
    private fun tryMistralOcr(filePath: Path): OcrResult? {
        return try {
            val result = mistralOcrClient.ocr(filePath)
            if (result.markdown.isBlank()) {
                log.warn("Mistral OCR returned empty markdown, falling back")
                return null
            }
            OcrResult(
                text = result.markdown,
                engine = OcrEngine.MISTRAL_OCR,
                pageCount = result.pageCount
            )
        } catch (e: Exception) {
            log.warn("Mistral OCR failed: {}", e.message)
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
