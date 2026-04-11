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

    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val helvB = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private val helv = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val blue = Color(0, 51, 102)

    fun generateTC(dossier: DossierPaiement, request: FinalizeRequest): ByteArray {
        val doc = PDDocument()
        var page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val w = page.mediaBox.width
        val m = 40f
        var y = page.mediaBox.height - 50f
        var cs = PDPageContentStream(doc, page)

        val facture = dossier.facture

        // Title
        y = centered(cs, "Tableau de Controle financier des depenses d'investissement", w, y, helvB, 10f, blue)
        y -= 20f

        // Header box
        val hdr = listOf(
            "Societe Geree" to "MADAEF",
            "Numero et date de Facture" to s(facture?.let { "Facture N. ${it.numeroFacture ?: ""} du ${it.dateFacture?.format(dateFmt) ?: ""}" } ?: ""),
            "Beneficiaire" to s(dossier.fournisseur ?: facture?.fournisseur ?: "")
        )
        for ((label, value) in hdr) {
            text(cs, m, y, helvB, 8f, s(label)); text(cs, m + 130f, y, helv, 9f, value)
            y -= 14f
        }
        y -= 12f

        // Table header
        cs.setNonStrokingColor(blue)
        cs.addRect(m, y - 14f, w - 2 * m, 16f); cs.fill()
        cs.setNonStrokingColor(Color.WHITE)
        text(cs, m + 4f, y - 10f, helvB, 7f, "ELEMENTS CONTROLES")
        text(cs, w - m - 170f, y - 10f, helvB, 7f, "OBSERVATIONS")
        text(cs, w - m - 75f, y - 10f, helvB, 7f, "COMMENTAIRE")
        cs.setNonStrokingColor(Color.BLACK)
        y -= 18f

        // Points
        for ((i, pt) in request.points.withIndex()) {
            val lines = wrap(s(pt.description), 55)
            val blockH = (lines.size * 10f) + 8f

            // New page if needed
            if (y - blockH < 80f) {
                cs.close()
                page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                cs = PDPageContentStream(doc, page)
                y = page.mediaBox.height - 50f
            }

            // Zebra stripe
            if (i % 2 == 0) {
                cs.setNonStrokingColor(Color(245, 245, 250)); cs.addRect(m, y - blockH, w - 2 * m, blockH); cs.fill()
                cs.setNonStrokingColor(Color.BLACK)
            }

            // Description
            var ty = y - 10f
            for ((li, line) in lines.withIndex()) {
                val prefix = if (li == 0) "Point ${i + 1} : " else ""
                text(cs, m + 4f, ty, if (li == 0) helvB else helv, 7f, s(prefix + line))
                ty -= 10f
            }

            // Observation
            text(cs, w - m - 160f, y - 10f, helv, 8f, s(pt.observation))
            // Commentaire
            if (!pt.commentaire.isNullOrBlank()) text(cs, w - m - 72f, y - 10f, helv, 7f, s(pt.commentaire))

            // Border
            cs.setStrokingColor(Color(200, 200, 210)); cs.addRect(m, y - blockH, w - 2 * m, blockH); cs.stroke()
            y -= blockH
        }

        // Signature
        y -= 20f
        if (y < 80f) { cs.close(); page = PDPage(PDRectangle.A4); doc.addPage(page); cs = PDPageContentStream(doc, page); y = page.mediaBox.height - 50f }
        text(cs, m, y, helvB, 8f, "Nom et Prenom"); text(cs, w / 2, y, helvB, 8f, "Signature")
        y -= 14f
        text(cs, m, y, helv, 9f, s(request.signataire))
        text(cs, w / 2, y, helv, 8f, "Signe electroniquement le ${LocalDate.now().format(dateFmt)}")
        drawSignature(doc, cs, request.signatureBase64, w / 2, y - 50f)

        cs.close()
        return save(doc)
    }

    fun generateOP(dossier: DossierPaiement, request: FinalizeRequest): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val w = page.mediaBox.width
        val m = 40f
        var y = page.mediaBox.height - 50f

        val facture = dossier.facture
        val op = dossier.ordrePaiement
        val bc = dossier.bonCommande
        val contrat = dossier.contratAvenant

        val cs = PDPageContentStream(doc, page)

        y = centered(cs, "ORDRE DE PAIEMENT", w, y, helvB, 14f, blue)
        y -= 10f

        // OP number + date
        val opNum = op?.numeroOp ?: "___/____"
        val opDate = op?.dateEmission?.format(dateFmt) ?: LocalDate.now().format(dateFmt)
        text(cs, m, y, helvB, 9f, "OP N. : $opNum"); text(cs, w - m - 150f, y, helvB, 9f, "Date d'emission : $opDate")
        y -= 20f

        // Emetteur bar
        cs.setNonStrokingColor(Color(200, 230, 200)); cs.addRect(m, y - 14f, w - 2 * m, 18f); cs.fill()
        cs.setNonStrokingColor(Color.BLACK)
        text(cs, m + 4f, y - 10f, helvB, 8f, "Emetteur : Direction Comptabilite, Consolidation et Fiscalite")
        y -= 24f

        // Fields
        val rows = listOf(
            "Nature" to s(op?.natureOperation ?: if (dossier.type == DossierType.BC) "Fournitures" else "Prestation de services"),
            "Description" to s(op?.description ?: dossier.description ?: ""),
            "Ref" to s(op?.referenceFacture?.let { "Facture N. $it" } ?: facture?.let { "Facture N. ${it.numeroFacture} du ${it.dateFacture?.format(dateFmt)}" } ?: ""),
            "Ref SAGE" to s(op?.referenceSage ?: ""),
            "Beneficiaire" to s(op?.beneficiaire ?: dossier.fournisseur ?: ""),
            "RIB" to s(op?.rib ?: facture?.rib ?: ""),
            "Banque" to s(op?.banque ?: ""),
            "Montant" to fmtAmount(op?.montantOperation ?: dossier.montantTtc)
        )
        for ((label, value) in rows) {
            text(cs, m + 4f, y, helvB, 8f, label); text(cs, m + 100f, y, helv, 9f, value)
            y -= 14f
        }
        y -= 10f

        // Pieces justificatives
        text(cs, m, y, helvB, 8f, "Pieces justificatives jointes :")
        y -= 12f
        val pieces = mutableListOf<String>()
        facture?.let { pieces.add("Facture N. ${s(it.numeroFacture ?: "")} du ${it.dateFacture?.format(dateFmt) ?: ""}") }
        bc?.let { pieces.add("Bon de commande N. ${s(it.reference ?: "")} du ${it.dateBc?.format(dateFmt) ?: ""}") }
        contrat?.let { pieces.add("${s(it.referenceContrat ?: "Contrat")} ${it.numeroAvenant?.let { a -> "- Avenant N.$a" } ?: ""}") }
        pieces.add("Check list d'auto controle + Check list des pieces justificatives")
        pieces.add("Tableau de controle")
        for (p in pieces) { text(cs, m + 10f, y, helv, 8f, "- $p"); y -= 12f }
        y -= 10f

        // Synthese bar
        cs.setNonStrokingColor(Color(200, 230, 200)); cs.addRect(m, y - 14f, w - 2 * m, 18f); cs.fill()
        cs.setNonStrokingColor(Color.BLACK)
        text(cs, m + 4f, y - 10f, helvB, 8f, "Synthese du Controleur Financier DCCF")
        y -= 28f

        val synthese = s(request.commentaireGeneral ?: op?.conclusionControleur ?: "Paiement valide")
        for (line in wrap(synthese, 90)) { text(cs, m + 4f, y, helv, 8f, line); y -= 11f }
        y -= 8f

        text(cs, m, y, helvB, 9f, "Conclusion : Paiement valide")
        y -= 20f

        // Signature
        text(cs, m, y, helv, 8f, "Signe electroniquement par ${s(request.signataire)} le ${LocalDate.now().format(dateFmt)}")
        drawSignature(doc, cs, request.signatureBase64, m, y - 55f)

        cs.close()
        return save(doc)
    }

    // --- Helpers ---

    private fun text(cs: PDPageContentStream, x: Float, y: Float, font: PDType1Font, size: Float, text: String) {
        cs.setFont(font, size); cs.beginText(); cs.newLineAtOffset(x, y); cs.showText(text); cs.endText()
    }

    private fun centered(cs: PDPageContentStream, text: String, pw: Float, y: Float, font: PDType1Font, size: Float, color: Color): Float {
        cs.setFont(font, size); cs.setNonStrokingColor(color)
        val tw = font.getStringWidth(text) / 1000f * size
        cs.beginText(); cs.newLineAtOffset((pw - tw) / 2f, y); cs.showText(text); cs.endText()
        cs.setNonStrokingColor(Color.BLACK)
        return y - size - 4f
    }

    private fun drawSignature(doc: PDDocument, cs: PDPageContentStream, base64: String?, x: Float, y: Float) {
        if (base64.isNullOrBlank()) return
        try {
            val bytes = java.util.Base64.getDecoder().decode(base64.substringAfter(","))
            val img = PDImageXObject.createFromByteArray(doc, bytes, "signature")
            cs.drawImage(img, x, y, 120f, 45f)
        } catch (_: Exception) {}
    }

    private fun save(doc: PDDocument): ByteArray {
        val out = ByteArrayOutputStream()
        doc.save(out); doc.close()
        return out.toByteArray()
    }

    private fun wrap(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val lines = mutableListOf<String>()
        var rem = text
        while (rem.length > max) {
            val b = rem.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
            lines.add(rem.substring(0, b)); rem = rem.substring(b).trimStart()
        }
        if (rem.isNotBlank()) lines.add(rem)
        return lines
    }

    private fun fmtAmount(a: java.math.BigDecimal?): String {
        if (a == null) return ""
        val parts = a.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().split(".")
        val intPart = parts[0].reversed().chunked(3).joinToString(" ").reversed()
        return "$intPart,${parts.getOrElse(1) { "00" }} DH"
    }

    // Strip non-WinAnsi chars for PDFBox Standard14 fonts
    private fun s(text: String): String = text
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
        .replace(Regex("[^\\x00-\\xFF]"), "")
}
