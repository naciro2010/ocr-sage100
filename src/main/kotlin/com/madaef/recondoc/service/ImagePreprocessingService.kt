package com.madaef.recondoc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ColorConvertOp
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Service de preprocessing d'images pour ameliorer la precision OCR.
 * Pipeline: Grayscale -> Deskew -> Denoise -> Binarize -> Scale DPI
 */
@Service
class ImagePreprocessingService(
    @Value("\${ocr.preprocessing.enabled:true}") private val enabled: Boolean,
    @Value("\${ocr.preprocessing.deskew:true}") private val deskewEnabled: Boolean,
    @Value("\${ocr.preprocessing.binarize:true}") private val binarizeEnabled: Boolean,
    @Value("\${ocr.preprocessing.denoise:true}") private val denoiseEnabled: Boolean,
    @Value("\${ocr.preprocessing.scale-dpi:300}") private val targetDpi: Int
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Applique le pipeline complet de preprocessing sur une image.
     * Retourne l'image preprocessee prete pour Tesseract.
     */
    fun preprocess(image: BufferedImage): BufferedImage {
        if (!enabled) {
            log.debug("Image preprocessing is disabled")
            return image
        }

        log.info("Starting image preprocessing ({}x{})", image.width, image.height)
        var result = image

        // Step 1: Convertir en niveaux de gris
        result = toGrayscale(result)

        // Step 2: Upscale si resolution trop basse (images < 200px de large)
        result = scaleIfNeeded(result)

        // Step 3: Deskew (correction d'inclinaison)
        if (deskewEnabled) {
            result = deskew(result)
        }

        // Step 4: Denoise (suppression bruit)
        if (denoiseEnabled) {
            result = denoise(result)
        }

        // Step 5: Binarisation adaptive (noir et blanc)
        if (binarizeEnabled) {
            result = adaptiveBinarize(result)
        }

        log.info("Preprocessing complete ({}x{})", result.width, result.height)
        return result
    }

    /**
     * Convertit une image en niveaux de gris.
     */
    private fun toGrayscale(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_BYTE_GRAY) return image
        val grayOp = ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null)
        val gray = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        grayOp.filter(image, gray)
        return gray
    }

    /**
     * Upscale l'image si trop petite pour un bon OCR.
     * Tesseract fonctionne mieux a 300 DPI minimum.
     */
    private fun scaleIfNeeded(image: BufferedImage): BufferedImage {
        // More conservative: only scale very small images (< 600px)
        // Aggressive scaling of low-quality scans makes them blurrier
        val minWidth = 600
        if (image.width >= minWidth) return image

        val scale = minWidth.toDouble() / image.width
        val newWidth = (image.width * scale).roundToInt()
        val newHeight = (image.height * scale).roundToInt()

        log.debug("Scaling image from {}x{} to {}x{}", image.width, image.height, newWidth, newHeight)

        val scaled = BufferedImage(newWidth, newHeight, image.type)
        val g = scaled.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(image, 0, 0, newWidth, newHeight, null)
        g.dispose()
        return scaled
    }

    /**
     * Correction d'inclinaison (deskew) basee sur la projection horizontale.
     * Detecte l'angle de rotation et corrige.
     */
    private fun deskew(image: BufferedImage): BufferedImage {
        val angle = detectSkewAngle(image)
        if (abs(angle) < 0.5) {
            log.debug("Skew angle negligible ({} degrees), skipping deskew", "%.2f".format(angle))
            return image
        }
        log.debug("Detected skew angle: {} degrees, correcting", "%.2f".format(angle))
        return rotateImage(image, -angle)
    }

    /**
     * Detection de l'angle d'inclinaison par analyse de projection horizontale.
     * Teste des angles de -5 a +5 degres et cherche le maximum de variance.
     */
    private fun detectSkewAngle(image: BufferedImage): Double {
        var bestAngle = 0.0
        var bestVariance = 0.0

        // Tester des angles de -15 a +15 degres par pas de 1.0 (coarse scan)
        var angle = -15.0
        while (angle <= 15.0) {
            val rotated = rotateImage(image, angle)
            val variance = calculateHorizontalProjectionVariance(rotated)
            if (variance > bestVariance) {
                bestVariance = variance
                bestAngle = angle
            }
            angle += 1.0
        }

        // Affiner autour du meilleur angle par pas de 0.1
        val refinedStart = bestAngle - 0.5
        val refinedEnd = bestAngle + 0.5
        var refinedAngle = refinedStart
        while (refinedAngle <= refinedEnd) {
            val rotated = rotateImage(image, refinedAngle)
            val variance = calculateHorizontalProjectionVariance(rotated)
            if (variance > bestVariance) {
                bestVariance = variance
                bestAngle = refinedAngle
            }
            refinedAngle += 0.1
        }

        return bestAngle
    }

    /**
     * Calcule la variance de la projection horizontale (somme des pixels noirs par ligne).
     * Un document bien aligne a une variance plus elevee.
     */
    private fun calculateHorizontalProjectionVariance(image: BufferedImage): Double {
        val rowSums = IntArray(image.height)
        val threshold = 128

        for (y in 0 until image.height) {
            var sum = 0
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y) and 0xFF
                if (pixel < threshold) sum++
            }
            rowSums[y] = sum
        }

        val mean = rowSums.average()
        return rowSums.map { (it - mean) * (it - mean) }.average()
    }

    /**
     * Rotation d'une image autour de son centre.
     */
    private fun rotateImage(image: BufferedImage, angleDegrees: Double): BufferedImage {
        val rads = Math.toRadians(angleDegrees)
        val rotated = BufferedImage(image.width, image.height, image.type)
        val g = rotated.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.rotate(rads, image.width / 2.0, image.height / 2.0)
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return rotated
    }

    /**
     * Debruitage adaptatif: 5x5 pour scans fax bruyants, 3x3 standard sinon.
     * Detecte le niveau de bruit et adapte la force du filtre.
     */
    private fun denoise(image: BufferedImage): BufferedImage {
        // Estimate noise level by checking variance of small region
        val noiseLevel = estimateNoiseLevel(image)
        log.debug("Estimated noise level: {}", "%.2f".format(noiseLevel))

        val kernel = if (noiseLevel > 500.0) {
            // High noise (fax scans, poor quality) — stronger 5x5 Gaussian
            log.debug("Using strong 5x5 denoise for high-noise image")
            floatArrayOf(
                1f/273f,  4f/273f,  7f/273f,  4f/273f,  1f/273f,
                4f/273f, 16f/273f, 26f/273f, 16f/273f,  4f/273f,
                7f/273f, 26f/273f, 41f/273f, 26f/273f,  7f/273f,
                4f/273f, 16f/273f, 26f/273f, 16f/273f,  4f/273f,
                1f/273f,  4f/273f,  7f/273f,  4f/273f,  1f/273f
            )
        } else {
            // Normal noise — standard 3x3 Gaussian
            floatArrayOf(
                1f / 16f, 2f / 16f, 1f / 16f,
                2f / 16f, 4f / 16f, 2f / 16f,
                1f / 16f, 2f / 16f, 1f / 16f
            )
        }
        val size = if (noiseLevel > 500.0) 5 else 3
        val gaussianKernel = Kernel(size, size, kernel)
        val op = ConvolveOp(gaussianKernel, ConvolveOp.EDGE_NO_OP, null)
        val denoised = BufferedImage(image.width, image.height, image.type)
        op.filter(image, denoised)
        return denoised
    }

    /**
     * Estimate noise level by computing local variance in a sample region.
     */
    private fun estimateNoiseLevel(image: BufferedImage): Double {
        val sampleSize = minOf(100, image.width, image.height)
        val startX = (image.width - sampleSize) / 2
        val startY = (image.height - sampleSize) / 2
        val pixels = mutableListOf<Int>()
        for (y in startY until startY + sampleSize) {
            for (x in startX until startX + sampleSize) {
                pixels.add(image.getRGB(x, y) and 0xFF)
            }
        }
        val mean = pixels.average()
        return pixels.map { (it - mean) * (it - mean) }.average()
    }

    /**
     * Binarisation adaptive (methode Sauvola simplifiee).
     * Chaque pixel est compare a la moyenne locale de son voisinage.
     * Gere bien l'eclairage inegal des scans de factures.
     */
    private fun adaptiveBinarize(image: BufferedImage): BufferedImage {
        val windowSize = 25  // Larger window for uneven lighting (common in fax/scan)
        val k = 0.15 // More aggressive contrast stretching for faded documents
        val halfWindow = windowSize / 2

        val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_BINARY)

        // Creer une table d'integration pour calcul rapide des moyennes
        val integralSum = Array(image.height + 1) { LongArray(image.width + 1) }
        val integralSqSum = Array(image.height + 1) { LongArray(image.width + 1) }

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = (image.getRGB(x, y) and 0xFF).toLong()
                integralSum[y + 1][x + 1] = pixel + integralSum[y][x + 1] + integralSum[y + 1][x] - integralSum[y][x]
                integralSqSum[y + 1][x + 1] = pixel * pixel + integralSqSum[y][x + 1] + integralSqSum[y + 1][x] - integralSqSum[y][x]
            }
        }

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val x1 = maxOf(0, x - halfWindow)
                val y1 = maxOf(0, y - halfWindow)
                val x2 = minOf(image.width - 1, x + halfWindow)
                val y2 = minOf(image.height - 1, y + halfWindow)

                val count = ((x2 - x1 + 1) * (y2 - y1 + 1)).toLong()
                val sum = integralSum[y2 + 1][x2 + 1] - integralSum[y1][x2 + 1] - integralSum[y2 + 1][x1] + integralSum[y1][x1]
                val sqSum = integralSqSum[y2 + 1][x2 + 1] - integralSqSum[y1][x2 + 1] - integralSqSum[y2 + 1][x1] + integralSqSum[y1][x1]

                val mean = sum.toDouble() / count
                val variance = (sqSum.toDouble() / count) - (mean * mean)
                val stddev = kotlin.math.sqrt(maxOf(0.0, variance))

                // Seuil Sauvola: T = mean * (1 + k * (stddev / 128 - 1))
                val threshold = mean * (1.0 + k * (stddev / 128.0 - 1.0))

                val pixel = image.getRGB(x, y) and 0xFF
                val color = if (pixel > threshold) Color.WHITE.rgb else Color.BLACK.rgb
                result.setRGB(x, y, color)
            }
        }

        return result
    }

    /**
     * Convertit un InputStream image en BufferedImage.
     */
    fun readImage(inputStream: InputStream): BufferedImage? {
        return try {
            ImageIO.read(inputStream)
        } catch (e: Exception) {
            log.warn("Failed to read image: {}", e.message)
            null
        }
    }

    /**
     * Convertit un BufferedImage en InputStream PNG.
     */
    fun toInputStream(image: BufferedImage): InputStream {
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return ByteArrayInputStream(baos.toByteArray())
    }
}
