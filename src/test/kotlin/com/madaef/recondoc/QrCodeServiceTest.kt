package com.madaef.recondoc

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.madaef.recondoc.service.QrCodeService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrCodeServiceTest {

    private val service = QrCodeService(renderDpi = 200, maxPagesToScan = 4)

    @Test
    fun `scan decodes QR from generated PNG`(@TempDir tmp: Path) {
        val payload = "https://www.tax.gov.ma/verify?code=18a50bf6baf372bd"
        val png = tmp.resolve("qr.png")
        writeQr(payload, png)

        val result = service.scan(png, "qr.png")

        assertTrue(result.found, "QR should be decoded: error=${result.error}")
        assertEquals(payload, result.primary)
    }

    @Test
    fun `scan returns error for unsupported format`(@TempDir tmp: Path) {
        val f = tmp.resolve("doc.txt")
        f.writeBytes("not an image".toByteArray())
        val result = service.scan(f, "doc.txt")
        assertNull(result.primary)
        assertNotNull(result.error)
    }

    @Test
    fun `extractVerificationCode handles URL with code param`() {
        val code = QrCodeService.extractVerificationCode(
            "https://www.tax.gov.ma/verif?code=18a50bf6baf372bd&lang=fr"
        )
        assertEquals("18a50bf6baf372bd", code)
    }

    @Test
    fun `extractVerificationCode falls back to longest hex token`() {
        val code = QrCodeService.extractVerificationCode("ref:12 payload:deadbeefcafebabe1234")
        assertEquals("deadbeefcafebabe1234", code)
    }

    @Test
    fun `extractVerificationCode returns raw short payload when no URL`() {
        assertEquals("ABC123", QrCodeService.extractVerificationCode("ABC123"))
    }

    @Test
    fun `extractHost returns host for http URL`() {
        assertEquals("www.tax.gov.ma",
            QrCodeService.extractHost("https://www.tax.gov.ma/verify?code=x"))
    }

    @Test
    fun `isOfficialDgiHost accepts tax gov ma subdomains only`() {
        assertTrue(QrCodeService.isOfficialDgiHost("tax.gov.ma"))
        assertTrue(QrCodeService.isOfficialDgiHost("www.tax.gov.ma"))
        assertTrue(!QrCodeService.isOfficialDgiHost("phishing.example.com"))
        assertTrue(!QrCodeService.isOfficialDgiHost(null))
    }

    private fun writeQr(payload: String, output: Path) {
        val matrix = MultiFormatWriter().encode(
            payload, BarcodeFormat.QR_CODE, 400, 400,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        )
        MatrixToImageWriter.writeToPath(matrix, "PNG", output)
    }
}
