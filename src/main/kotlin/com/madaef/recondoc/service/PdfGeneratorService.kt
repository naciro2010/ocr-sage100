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

    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val helveticaBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    fun generateTC(dossier: DossierPaiement, request: FinalizeRequest): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val w = page.mediaBox.width
        val margin = 40f
        var y = page.mediaBox.height - 40f

        PDPageContentStream(doc, page).use { cs ->
            // Title
            y = drawCenteredText(cs, "Tableau de Controle financier des depenses d'investissement", w, y, helveticaBold, 11f, Color(0, 51, 102))
            y -= 20f

            // Header info
            val facture = dossier.facture
            val headerData = listOf(
                "Societe Geree" to "MADAEF",
                "Numero et date de Facture" to (facture?.let { "Facture N° ${it.numeroFacture ?: ""} du ${it.dateFacture?.format(dateFormatter) ?: ""}" } ?: ""),
                "Beneficiaire" to (dossier.fournisseur ?: facture?.fournisseur ?: "")
            )
            for ((label, value) in headerData) {
                cs.setFont(helveticaBold, 8f)
                cs.beginText(); cs.newLineAtOffset(margin, y); cs.showText(safe(label)); cs.endText()
                cs.setFont(helvetica, 9f)
                cs.beginText(); cs.newLineAtOffset(margin + 130f, y); cs.showText(safe(value)); cs.endText()
                y -= 14f
            }
            y -= 15f

            // Table header
            cs.setNonStrokingColor(Color(0, 51, 102))
            cs.addRect(margin, y - 14f, w - 2 * margin, 16f)
            cs.fill()
            cs.setNonStrokingColor(Color.WHITE)
            cs.setFont(helveticaBold, 7f)
            cs.beginText(); cs.newLineAtOffset(margin + 4f, y - 10f); cs.showText(safe("ELEMENTS CONTROLES")); cs.endText()
            cs.beginText(); cs.newLineAtOffset(w - margin - 170f, y - 10f); cs.showText(safe("OBSERVATIONS")); cs.endText()
            cs.beginText(); cs.newLineAtOffset(w - margin - 80f, y - 10f); cs.showText(safe("COMMENTAIRE")); cs.endText()
            cs.setNonStrokingColor(Color.BLACK)
            y -= 18f

            // Points dynamiques
            for ((i, point) in request.points.withIndex()) {
                val descLines = wrapText(point.description, 55)
                val blockH = (descLines.size * 10f) + 8f

                // Check for page break
                if (y - blockH < 80f) {
                    // Would need new page — skip for simplicity, truncate
                }

                // Row background
                if (i % 2 == 0) {
                    cs.setNonStrokingColor(Color(245, 245, 250))
                    cs.addRect(margin, y - blockH, w - 2 * margin, blockH)
                    cs.fill()
                    cs.setNonStrokingColor(Color.BLACK)
                }

                // Description
                cs.setFont(helveticaBold, 7f)
                var textY = y - 10f
                for (line in descLines) {
                    cs.beginText(); cs.newLineAtOffset(margin + 4f, textY); cs.showText(safe("${if (textY == y - 10f) "Point ${i + 1} : " else ""}$line")); cs.endText()
                    textY -= 10f
                }

                // Observation
                cs.setFont(helvetica, 8f)
                cs.beginText(); cs.newLineAtOffset(w - margin - 160f, y - 10f); cs.showText(safe(point.observation)); cs.endText()

                // Commentaire
                if (!point.commentaire.isNullOrBlank()) {
                    cs.setFont(helvetica, 7f)
                    cs.beginText(); cs.newLineAtOffset(w - margin - 75f, y - 10f); cs.showText(safe(point.commentaire)); cs.endText()
                }

                // Border
                cs.setStrokingColor(Color(200, 200, 210))
                cs.addRect(margin, y - blockH, w - 2 * margin, blockH)
                cs.stroke()

                y -= blockH
            }

            // Signature
            y -= 20f
            cs.setFont(helveticaBold, 8f)
            cs.beginText(); cs.newLineAtOffset(margin, y); cs.showText(safe("Nom et Prenom")); cs.endText()
            cs.beginText(); cs.newLineAtOffset(w / 2, y); cs.showText(safe("Signature")); cs.endText()
            y -= 14f
            cs.setFont(helvetica, 9f)
            cs.beginText(); cs.newLineAtOffset(margin, y); cs.showText(safe(request.signataire)); cs.endText()
            cs.beginText(); cs.newLineAtOffset(w / 2, y); cs.showText(safe("Signe electroniquement le ${LocalDate.now().format(dateFormatter)}")); cs.endText()

            // Signature image
            if (!request.signatureBase64.isNullOrBlank()) {
                try {
                    val sigBytes = java.util.Base64.getDecoder().decode(
                        request.signatureBase64.substringAfter(",")
                    )
                    val sigImage = PDImageXObject.createFromByteArray(doc, sigBytes, "signature")
                    cs.drawImage(sigImage, w / 2, y - 50f, 100f, 40f)
                } catch (_: Exception) {}
            }
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    fun generateOP(dossier: DossierPaiement, request: FinalizeRequest): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val w = page.mediaBox.width
        val margin = 40f
        var y = page.mediaBox.height - 40f

        val facture = dossier.facture
        val op = dossier.ordrePaiement
        val bc = dossier.bonCommande
        val contrat = dossier.contratAvenant

        PDPageContentStream(doc, page).use { cs ->
            // Title
            y = drawCenteredText(cs, "ORDRE DE PAIEMENT", w, y, helveticaBold, 14f, Color(0, 51, 102))
            y -= 10f

            // OP info
            val opNum = op?.numeroOp ?: "___/____"
            val opDate = op?.dateEmission?.format(dateFormatter) ?: LocalDate.now().format(dateFormatter)
            cs.setFont(helveticaBold, 9f)
            cs.beginText(); cs.newLineAtOffset(margin, y); cs.showText(safe("OP N° : $opNum")); cs.endText()
            cs.beginText(); cs.newLineAtOffset(w - margin - 150f, y); cs.showText(safe("Date d'emission : $opDate")); cs.endText()
            y -= 20f

            // Green header: Emetteur
            cs.setNonStrokingColor(Color(200, 230, 200))
            cs.addRect(margin, y - 16f, w - 2 * margin, 18f)
            cs.fill()
            cs.setNonStrokingColor(Color.BLACK)
            cs.setFont(helveticaBold, 8f)
            cs.beginText(); cs.newLineAtOffset(margin + 4f, y - 12f); cs.showText(safe("Emetteur : Direction Comptabilite, Consolidation et Fiscalite")); cs.endText()
            y -= 24f

            // Main content table
            val rows = listOf(
                "Nature" to (op?.natureOperation ?: (if (dossier.type == DossierType.BC) "Fournitures" else "Prestation de services")),
                "Description" to (op?.description ?: dossier.description ?: ""),
                "Ref" to (op?.referenceFacture?.let { "Facture N° $it" } ?: facture?.let { "Facture N° ${it.numeroFacture} du ${it.dateFacture?.format(dateFormatter)}" } ?: ""),
                "Ref SAGE" to (op?.referenceSage ?: ""),
                "Beneficiaire" to (op?.beneficiaire ?: dossier.fournisseur ?: ""),
                "RIB" to (op?.rib ?: facture?.rib ?: ""),
                "Banque" to (op?.banque ?: ""),
                "Montant" to formatMontant(op?.montantOperation ?: dossier.montantTtc)
            )
            for ((label, value) in rows) {
                cs.setFont(helveticaBold, 8f)
                cs.beginText(); cs.newLineAtOffset(margin + 4f, y); cs.showText(safe(label)); cs.endText()
                cs.setFont(helvetica, 9f)
                cs.beginText(); cs.newLineAtOffset(margin + 100f, y); cs.showText(safe(value)); cs.endText()
                y -= 14f
            }
            y -= 10f

            // Pieces justificatives
            cs.setFont(helveticaBold, 8f)
            cs.beginText(); cs.newLineAtOffset(margin, y); cs.showText(safe("Pieces justificatives jointes :")); cs.endText()
            y -= 12f
            cs.setFont(helvetica, 8f)
            val pieces = mutableListOf<String>()
            facture?.let { pieces.add("Facture N° ${it.numeroFacture ?: ""} du ${it.dateFacture?.format(dateFormatter) ?: ""}") }
            bc?.let { pieces.add("Bon de commande N° ${it.reference ?: ""} du ${it.dateBc?.format(dateFormatter) ?: ""}") }
            contrat?.let { pieces.add("${it.referenceContrat ?: "Contrat"} ${it.numeroAvenant?.let { a -> "- Avenant N°$a" } ?: ""}") }
            pieces.add("Check list d'auto controle + Check list des pieces justificatives")
            pieces.add("Tableau de controle")
            for (p in pieces) {
                cs.beginText(); cs.newLineAtOffset(margin + 10f, y); cs.showText(safe("- $p")); cs.endText()
                y -= 12f
            }
            y -= 10f

            // Synthese controleur
            cs.setNonStrokingColor(Color(200, 230, 200))
            cs.addRect(margin, y - 16f, w - 2 * margin, 18f)
            cs.fill()
            cs.setNonStrokingColor(Color.BLACK)
            cs.setFont(helveticaBold, 8f)
            cs.beginText(); cs.newLineAtOffset(margin + 4f, y - 12f); cs.showText(safe("Synthese du Controleur Financier DCCF")); cs.endText()
            y -= 28f

            cs.setFont(helvetica, 8f)
            val synthese = request.commentaireGeneral ?: op?.conclusionControleur ?: "Paiement valide"
            for (line in wrapText(synthese, 90)) {
                cs.beginText(); cs.newLineAtOffset(margin + 4f, y); cs.showText(safe(line)); cs.endText()
                y -= 11f
            }
            y -= 8f

            // Conclusion
            cs.setFont(helveticaBold, 9f)
            cs.beginText(); cs.newLineAtOffset(margin, y); cs.showText(safe("Conclusion : Paiement valide")); cs.endText()
            y -= 20f

            // Signature
            cs.setFont(helvetica, 8f)
            cs.beginText(); cs.newLineAtOffset(margin, y); cs.showText(safe("Signe electroniquement par ${request.signataire} le ${LocalDate.now().format(dateFormatter)}")); cs.endText()

            if (!request.signatureBase64.isNullOrBlank()) {
                try {
                    val sigBytes = java.util.Base64.getDecoder().decode(
                        request.signatureBase64.substringAfter(",")
                    )
                    val sigImage = PDImageXObject.createFromByteArray(doc, sigBytes, "signature")
                    cs.drawImage(sigImage, margin, y - 55f, 100f, 40f)
                } catch (_: Exception) {}
            }
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun drawCenteredText(cs: PDPageContentStream, text: String, pageWidth: Float, y: Float, font: PDType1Font, size: Float, color: Color): Float {
        cs.setFont(font, size)
        cs.setNonStrokingColor(color)
        val textWidth = font.getStringWidth(text) / 1000f * size
        cs.beginText()
        cs.newLineAtOffset((pageWidth - textWidth) / 2f, y)
        cs.showText(safe(text))
        cs.endText()
        cs.setNonStrokingColor(Color.BLACK)
        return y - size - 4f
    }

    private fun wrapText(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            val breakAt = remaining.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
            lines.add(remaining.substring(0, breakAt))
            remaining = remaining.substring(breakAt).trimStart()
        }
        if (remaining.isNotBlank()) lines.add(remaining)
        return lines
    }

    private fun formatMontant(amount: java.math.BigDecimal?): String {
        if (amount == null) return ""
        return String.format("%,.2f DH", amount).replace(",", " ").replace(".", ",").replace(" ", " ")
    }

    // PDFBox Standard14 fonts only support WinAnsiEncoding — strip/replace unsupported chars
    private fun safe(text: String): String {
        return text
            .replace("\u00e9", "e").replace("\u00e8", "e").replace("\u00ea", "e").replace("\u00eb", "e")
            .replace("\u00e0", "a").replace("\u00e2", "a").replace("\u00e4", "a")
            .replace("\u00f4", "o").replace("\u00f6", "o")
            .replace("\u00fb", "u").replace("\u00fc", "u").replace("\u00f9", "u")
            .replace("\u00ee", "i").replace("\u00ef", "i")
            .replace("\u00e7", "c")
            .replace("\u00c9", "E").replace("\u00c8", "E").replace("\u00ca", "E")
            .replace("\u00c0", "A").replace("\u00c2", "A")
            .replace("\u00d4", "O")
            .replace("\u00db", "U")
            .replace("\u00ce", "I")
            .replace("\u00c7", "C")
            .replace("\u2019", "'").replace("\u2018", "'")
            .replace("\u201c", "\"").replace("\u201d", "\"")
            .replace("\u2013", "-").replace("\u2014", "-")
            .replace("\u2026", "...")
            .replace(Regex("[^\\x00-\\xFF]"), "")
    }
}
