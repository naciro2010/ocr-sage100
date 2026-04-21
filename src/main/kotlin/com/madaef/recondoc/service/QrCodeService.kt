package com.madaef.recondoc.service

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Scans QR codes (and similar 2D barcodes) from PDF or image files. Used to
 * verify Moroccan DGI fiscal regularity attestations ("attestation de regularite
 * fiscale"), which include a QR code pointing to a verification URL on
 * tax.gov.ma containing the same "code de verification" printed on the page.
 */
@Service
class QrCodeService(
    @Value("\${qrcode.render-dpi:220}") private val renderDpi: Int,
    @Value("\${qrcode.max-pages:4}") private val maxPagesToScan: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true
    )

    data class QrScan(
        val codes: List<String>,
        val error: String? = null,
        val pagesScanned: Int = 0
    ) {
        val primary: String? get() = codes.firstOrNull()
        val found: Boolean get() = codes.isNotEmpty()
    }

    /**
     * Scan a file for QR codes. Returns all decoded payloads (usually one), or
     * an empty list with an error message if nothing could be read.
     */
    fun scan(filePath: Path, fileName: String): QrScan {
        if (!Files.exists(filePath)) {
            return QrScan(codes = emptyList(), error = "Fichier introuvable")
        }
        val lower = fileName.lowercase()
        return try {
            when {
                lower.endsWith(".pdf") -> scanPdf(filePath)
                isImage(lower) -> scanImage(filePath)
                else -> QrScan(codes = emptyList(), error = "Format non supporte pour QR (${lower.substringAfterLast('.')})")
            }
        } catch (e: Exception) {
            log.warn("QR scan failed for {}: {}", fileName, e.message)
            QrScan(codes = emptyList(), error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun scanPdf(filePath: Path): QrScan {
        Loader.loadPDF(filePath.toFile()).use { document ->
            val renderer = PDFRenderer(document)
            val pageCount = document.pages.count.coerceAtMost(maxPagesToScan)
            val aggregated = linkedSetOf<String>()
            for (page in 0 until pageCount) {
                val image = renderer.renderImageWithDPI(page, renderDpi.toFloat(), ImageType.RGB)
                aggregated += decode(image)
                if (aggregated.isNotEmpty()) {
                    return QrScan(codes = aggregated.toList(), pagesScanned = page + 1)
                }
            }
            return QrScan(codes = aggregated.toList(), pagesScanned = pageCount,
                error = if (aggregated.isEmpty()) "Aucun QR code lisible" else null)
        }
    }

    private fun scanImage(filePath: Path): QrScan {
        val image = ImageIO.read(filePath.toFile())
            ?: return QrScan(codes = emptyList(), error = "Image illisible")
        val codes = decode(image)
        return QrScan(
            codes = codes,
            pagesScanned = 1,
            error = if (codes.isEmpty()) "Aucun QR code lisible" else null
        )
    }

    private fun decode(image: BufferedImage): List<String> {
        val reader = MultiFormatReader().also { it.setHints(hints) }
        val source = BufferedImageLuminanceSource(image)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        // Single-QR fast path — cheapest and covers the attestation format.
        try {
            val result = reader.decodeWithState(bitmap)
            val text = result.text?.trim().orEmpty()
            if (text.isNotEmpty()) return listOf(text)
        } catch (_: NotFoundException) {
            // fall through to multi reader
        } catch (e: Exception) {
            log.debug("Primary QR decode error: {}", e.message)
        }

        // Multi-barcode path — handles documents with more than one QR.
        return try {
            val multi = GenericMultipleBarcodeReader(MultiFormatReader().also { it.setHints(hints) })
            multi.decodeMultiple(bitmap, hints)
                .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
        } catch (_: NotFoundException) {
            emptyList()
        } catch (e: Exception) {
            log.debug("Multi QR decode error: {}", e.message)
            emptyList()
        }
    }

    private fun isImage(lowerName: String): Boolean =
        lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
        lowerName.endsWith(".tif") || lowerName.endsWith(".tiff") || lowerName.endsWith(".bmp") ||
        lowerName.endsWith(".webp")

    companion object {
        private val HEX_CODE_RE = Regex("[0-9a-fA-F]{12,}")
        private val QUERY_PARAM_RE = Regex("(?i)(?:code|c|ref|token|verif(?:ication)?)=([^&#\\s]+)")
        private val NUMERO_RE = Regex("\\d{3,4}[\\s|/-]*\\d{4}[\\s|/-]*\\d{2,6}")

        /**
         * Extract the attestation's "code de verification" from a QR payload.
         * DGI attestations embed a URL like
         *   https://www.tax.gov.ma/...?code=18a50bf6baf372bd
         * or sometimes just the hex code itself. We accept both shapes.
         */
        fun extractVerificationCode(payload: String?): String? {
            if (payload.isNullOrBlank()) return null
            QUERY_PARAM_RE.find(payload)?.groupValues?.getOrNull(1)?.let { return it.trim() }
            HEX_CODE_RE.findAll(payload).maxByOrNull { it.value.length }?.value?.let { return it }
            return payload.trim().takeIf { it.isNotBlank() && it.length <= 64 }
        }

        fun extractHost(payload: String?): String? {
            if (payload.isNullOrBlank()) return null
            return try { URI(payload.trim()).host?.lowercase() } catch (_: Exception) { null }
        }

        fun extractNumero(payload: String?): String? {
            if (payload.isNullOrBlank()) return null
            return NUMERO_RE.find(payload)?.value
        }

        fun isOfficialDgiHost(host: String?): Boolean {
            if (host.isNullOrBlank()) return false
            val h = host.lowercase().trim()
            return h == "tax.gov.ma" || h.endsWith(".tax.gov.ma") ||
                h == "www.tax.gov.ma"
        }

        /**
         * The DGI publishes the verification page at https://attestation.tax.gov.ma/
         * (the "site des impots"). We treat that exact host as the canonical one and
         * log a soft warning for other tax.gov.ma subdomains so reviewers notice
         * when the DGI rotates the URL.
         */
        fun isCanonicalAttestationHost(host: String?): Boolean =
            host?.lowercase()?.trim() == "attestation.tax.gov.ma"

        /**
         * Scheme-level safety check for the QR payload. The DGI encodes an HTTPS
         * URL; anything else — `javascript:`, `data:`, `file:`, embedded
         * credentials, or suspicious control characters — is almost certainly a
         * tampered or malicious QR, and the rule must flag it NON_CONFORME
         * regardless of whether the printed code happens to match.
         */
        fun assessPayloadSafety(payload: String?): PayloadSafety {
            if (payload.isNullOrBlank()) return PayloadSafety(PayloadVerdict.ABSENT, null)
            val trimmed = payload.trim()
            if (trimmed.any { it.code in 0..31 && it != '\t' }) {
                return PayloadSafety(PayloadVerdict.DANGEROUS, "Caracteres de controle detectes dans le QR")
            }
            // Bare hex code (no scheme) — accepted if it matches the printed code later.
            if (!trimmed.contains(":") && !trimmed.contains("/")) {
                return PayloadSafety(PayloadVerdict.SAFE, null)
            }
            val uri = try { URI(trimmed) } catch (_: Exception) {
                return PayloadSafety(PayloadVerdict.DANGEROUS, "URL du QR malformee: $trimmed")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme in BLOCKED_SCHEMES) {
                return PayloadSafety(PayloadVerdict.DANGEROUS, "Schema interdit dans le QR : $scheme")
            }
            if (scheme != null && scheme != "https" && scheme != "http") {
                return PayloadSafety(PayloadVerdict.DANGEROUS, "Schema non supporte : $scheme (attendu https)")
            }
            if (scheme == "http") {
                return PayloadSafety(PayloadVerdict.UNSAFE, "URL non chiffree (http) : redirection possible")
            }
            if (!uri.userInfo.isNullOrBlank()) {
                return PayloadSafety(PayloadVerdict.DANGEROUS, "Identifiants embarques dans l'URL du QR")
            }
            return PayloadSafety(PayloadVerdict.SAFE, null)
        }

        private val BLOCKED_SCHEMES = setOf(
            "javascript", "data", "vbscript", "file", "jar", "ftp", "blob", "about"
        )

        enum class PayloadVerdict { SAFE, UNSAFE, DANGEROUS, ABSENT }
        data class PayloadSafety(val verdict: PayloadVerdict, val reason: String?)
    }
}
