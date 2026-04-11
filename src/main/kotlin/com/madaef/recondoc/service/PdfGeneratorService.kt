package com.madaef.recondoc.service

import com.madaef.recondoc.entity.dossier.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Service
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class FinalizeRequest(
    val points: List<ControlPoint>,
    val signataire: String,
    val signatureBase64: String? = null,
    val commentaireGeneral: String? = null
)

data class ControlPoint(
    val description: String,
    val observation: String,
    val commentaire: String? = null
)

@Service
class PdfGeneratorService {

    private val df = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private val norm = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val blue = Color(0, 51, 153)
    private val lightBg = Color(242, 242, 248)

    // Column positions for TC table
    private val colDesc = 45f      // Description column start
    private val colObs = 400f      // Observations column start
    private val colCom = 480f      // Commentaire column start
    private val rightEdge = 560f   // Right edge

    fun generateTC(dossier: DossierPaiement, request: FinalizeRequest): ByteArray {
        val doc = PDDocument()
        var page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val m = 35f
        var y = 790f
        var cs = PDPageContentStream(doc, page)

        val facture = dossier.facture

        // Title
        y = drawCentered(cs, "Tableau de Controle financier des depenses d'investissement", 595f, y, bold, 11f, blue)
        y -= 25f

        // Header info box
        val headers = listOf(
            "Societe Geree" to "MADAEF",
            "Numero et date de Facture" to s(facture?.let { "Facture N. ${it.numeroFacture ?: ""} du ${it.dateFacture?.format(df) ?: ""}" } ?: ""),
            "Beneficiaire" to s(dossier.fournisseur ?: facture?.fournisseur ?: "")
        )
        for ((lbl, v) in headers) {
            drawText(cs, m, y, bold, 8f, s(lbl))
            drawText(cs, m + 140f, y, norm, 9f, v)
            y -= 13f
        }
        y -= 15f

        // Table header row
        cs.setNonStrokingColor(blue)
        cs.addRect(m, y - 15f, rightEdge - m, 17f); cs.fill()
        cs.setNonStrokingColor(Color.WHITE)
        drawText(cs, colDesc, y - 11f, bold, 7.5f, "ELEMENTS CONTROLES")
        drawText(cs, colObs, y - 11f, bold, 7.5f, "OBSERVATIONS")
        drawText(cs, colCom, y - 11f, bold, 7.5f, "Commentaire (le cas echeant)")
        cs.setNonStrokingColor(Color.BLACK)
        y -= 19f

        // Table rows — each point
        for ((i, pt) in request.points.withIndex()) {
            val descLines = wrapText("Point ${i + 1} : ${s(pt.description)}", 58)
            val rowH = (descLines.size * 9f).coerceAtLeast(18f) + 6f

            // Page break
            if (y - rowH < 80f) {
                cs.close(); page = PDPage(PDRectangle.A4); doc.addPage(page)
                cs = PDPageContentStream(doc, page); y = 790f
            }

            // Zebra background
            if (i % 2 == 0) {
                cs.setNonStrokingColor(lightBg); cs.addRect(m, y - rowH, rightEdge - m, rowH); cs.fill()
                cs.setNonStrokingColor(Color.BLACK)
            }

            // Description text
            var ty = y - 10f
            for (line in descLines) {
                drawText(cs, colDesc, ty, norm, 7f, line)
                ty -= 9f
            }

            // Observation (centered in column)
            drawText(cs, colObs + 10f, y - 10f, norm, 8f, s(pt.observation))

            // Commentaire
            if (!pt.commentaire.isNullOrBlank()) {
                drawText(cs, colCom, y - 10f, norm, 7f, s(pt.commentaire.take(30)))
            }

            // Row border
            cs.setStrokingColor(Color(210, 210, 220))
            cs.addRect(m, y - rowH, rightEdge - m, rowH); cs.stroke()

            y -= rowH
        }

        y -= 5f
        drawText(cs, m, y, norm, 7f, "*Les controles doivent etre obligatoirement exhaustifs")
        y -= 25f

        // Signature section
        if (y < 80f) { cs.close(); page = PDPage(PDRectangle.A4); doc.addPage(page); cs = PDPageContentStream(doc, page); y = 790f }

        // Signature table header
        cs.setStrokingColor(Color(180, 180, 190))
        cs.addRect(m, y - 15f, 200f, 17f); cs.stroke()
        cs.addRect(m + 200f, y - 15f, rightEdge - m - 200f, 17f); cs.stroke()
        drawText(cs, m + 5f, y - 11f, bold, 8f, "Nom et Prenom")
        drawText(cs, m + 205f, y - 11f, bold, 8f, "Signature")
        y -= 17f

        // Signature row
        cs.addRect(m, y - 50f, 200f, 50f); cs.stroke()
        cs.addRect(m + 200f, y - 50f, rightEdge - m - 200f, 50f); cs.stroke()
        drawText(cs, m + 5f, y - 15f, norm, 9f, s(request.signataire))
        drawText(cs, m + 205f, y - 15f, norm, 7f, "Signe electroniquement le ${LocalDate.now().format(df)}")

        // Signature image
        drawSigImage(doc, cs, request.signatureBase64, m + 210f, y - 48f)

        cs.close()
        return savePdf(doc)
    }

    fun generateOP(dossier: DossierPaiement, request: FinalizeRequest): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val m = 35f
        var y = 790f
        val w = 560f

        val facture = dossier.facture
        val op = dossier.ordrePaiement
        val bc = dossier.bonCommande
        val contrat = dossier.contratAvenant

        val cs = PDPageContentStream(doc, page)

        y = drawCentered(cs, "ORDRE DE PAIEMENT", 595f, y, bold, 13f, blue)
        y -= 12f

        // OP number & date
        drawText(cs, m, y, bold, 9f, "OP N. : ${op?.numeroOp ?: "___/____"}")
        drawText(cs, 400f, y, bold, 9f, "Date d'emission : ${op?.dateEmission?.format(df) ?: LocalDate.now().format(df)}")
        y -= 18f

        // Green bar — Emetteur
        cs.setNonStrokingColor(Color(200, 230, 200)); cs.addRect(m, y - 14f, w - m, 16f); cs.fill()
        cs.setNonStrokingColor(Color.BLACK)
        drawText(cs, m + 4f, y - 10f, bold, 8f, "Emetteur : Direction Comptabilite, Consolidation et Fiscalite")
        y -= 22f

        // Key fields
        val fields = listOf(
            "Nature" to s(op?.natureOperation ?: if (dossier.type == DossierType.BC) "Fournitures" else "Prestation de services"),
            "Description" to s(op?.description ?: dossier.description ?: ""),
            "Ref" to s(op?.referenceFacture?.let { "Facture N. $it" } ?: facture?.let { "Facture N. ${it.numeroFacture} du ${it.dateFacture?.format(df)}" } ?: ""),
            "Ref SAGE" to s(op?.referenceSage ?: ""),
            "Beneficiaire" to s(op?.beneficiaire ?: dossier.fournisseur ?: ""),
            "RIB" to s(op?.rib ?: facture?.rib ?: ""),
            "Banque" to s(op?.banque ?: ""),
            "Montant" to fmtAmt(op?.montantOperation ?: dossier.montantTtc)
        )
        for ((lbl, v) in fields) {
            drawText(cs, m + 4f, y, bold, 8f, lbl)
            drawText(cs, m + 90f, y, norm, 9f, v)
            y -= 13f
        }
        y -= 8f

        // Pieces justificatives
        drawText(cs, m, y, bold, 8f, "Pieces justificatives jointes :")
        y -= 12f
        val pieces = mutableListOf<String>()
        facture?.let { pieces.add("Facture N. ${s(it.numeroFacture ?: "")} du ${it.dateFacture?.format(df) ?: ""}") }
        bc?.let { pieces.add("Bon de commande N. ${s(it.reference ?: "")} du ${it.dateBc?.format(df) ?: ""}") }
        contrat?.let { pieces.add("${s(it.referenceContrat ?: "Contrat")} ${it.numeroAvenant?.let { a -> "- Avenant N.$a" } ?: ""}") }
        pieces.add("Check list d'auto controle + Check list des pieces justificatives")
        pieces.add("Tableau de controle")
        for (p in pieces) { drawText(cs, m + 10f, y, norm, 7.5f, "- $p"); y -= 11f }
        y -= 8f

        // Green bar — Synthese
        cs.setNonStrokingColor(Color(200, 230, 200)); cs.addRect(m, y - 14f, w - m, 16f); cs.fill()
        cs.setNonStrokingColor(Color.BLACK)
        drawText(cs, m + 4f, y - 10f, bold, 8f, "Synthese du Controleur Financier DCCF")
        y -= 24f

        val synthese = s(request.commentaireGeneral ?: op?.conclusionControleur ?: "Paiement valide")
        for (line in wrapText(synthese, 85)) { drawText(cs, m + 4f, y, norm, 7.5f, line); y -= 10f }
        y -= 6f
        drawText(cs, m, y, bold, 9f, "Conclusion : Paiement valide")
        y -= 20f

        // Signature
        drawText(cs, m, y, norm, 8f, "Signe electroniquement par ${s(request.signataire)} le ${LocalDate.now().format(df)}")
        drawSigImage(doc, cs, request.signatureBase64, m, y - 50f)

        cs.close()
        return savePdf(doc)
    }

    // --- Helpers ---

    private fun drawText(cs: PDPageContentStream, x: Float, y: Float, font: PDType1Font, size: Float, text: String) {
        cs.setFont(font, size); cs.beginText(); cs.newLineAtOffset(x, y); cs.showText(text); cs.endText()
    }

    private fun drawCentered(cs: PDPageContentStream, text: String, pw: Float, y: Float, font: PDType1Font, size: Float, color: Color): Float {
        cs.setFont(font, size); cs.setNonStrokingColor(color)
        val tw = font.getStringWidth(text) / 1000f * size
        cs.beginText(); cs.newLineAtOffset((pw - tw) / 2f, y); cs.showText(text); cs.endText()
        cs.setNonStrokingColor(Color.BLACK)
        return y - size - 4f
    }

    private fun drawSigImage(doc: PDDocument, cs: PDPageContentStream, base64: String?, x: Float, y: Float) {
        if (base64.isNullOrBlank()) return
        try {
            val data = base64.substringAfter(",")
            val bytes = java.util.Base64.getDecoder().decode(data)
            val img = PDImageXObject.createFromByteArray(doc, bytes, "sig")
            cs.drawImage(img, x, y, 130f, 45f)
        } catch (_: Exception) {}
    }

    private fun savePdf(doc: PDDocument): ByteArray {
        val out = ByteArrayOutputStream(); doc.save(out); doc.close(); return out.toByteArray()
    }

    private fun wrapText(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val lines = mutableListOf<String>(); var r = text
        while (r.length > max) {
            val b = r.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
            lines.add(r.substring(0, b)); r = r.substring(b).trimStart()
        }
        if (r.isNotBlank()) lines.add(r); return lines
    }

    private fun fmtAmt(a: java.math.BigDecimal?): String {
        if (a == null) return ""
        val parts = a.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().split(".")
        val int = parts[0].reversed().chunked(3).joinToString(" ").reversed()
        return "$int,${parts.getOrElse(1) { "00" }} DH"
    }

    private fun s(t: String): String = t
        .replace('\u00e9', 'e').replace('\u00e8', 'e').replace('\u00ea', 'e').replace('\u00eb', 'e')
        .replace('\u00e0', 'a').replace('\u00e2', 'a').replace('\u00e4', 'a')
        .replace('\u00f4', 'o').replace('\u00f6', 'o').replace('\u00f9', 'u')
        .replace('\u00fb', 'u').replace('\u00fc', 'u').replace('\u00ee', 'i')
        .replace('\u00ef', 'i').replace('\u00e7', 'c').replace('\u00c9', 'E')
        .replace('\u00c8', 'E').replace('\u00ca', 'E').replace('\u00c0', 'A')
        .replace('\u00c2', 'A').replace('\u00d4', 'O').replace('\u00db', 'U')
        .replace('\u00ce', 'I').replace('\u00c7', 'C')
        .replace("\u2019", "'").replace("\u2018", "'")
        .replace("\u201c", "\"").replace("\u201d", "\"")
        .replace("\u2013", "-").replace("\u2014", "-")
        .replace("\\u2014", "-").replace("\\u2013", "-")
        .replace(Regex("[^\\x00-\\xFF]"), "")
}
