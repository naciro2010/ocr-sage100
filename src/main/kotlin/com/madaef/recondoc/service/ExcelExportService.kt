package com.madaef.recondoc.service

import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.StatutCheck
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/**
 * Excel report of a dossier: one sheet for the summary, one for the validation
 * details with rows colored by conformity. Format expected by MADAEF finance
 * for monthly reconciliation.
 */
@Service
class ExcelExportService {

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun exportDossier(dossier: DossierPaiement): ByteArray {
        XSSFWorkbook().use { wb ->
            val headerStyle = wb.createCellStyle().apply {
                val font = wb.createFont().apply { bold = true }
                setFont(font)
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            }
            val conformeStyle = wb.createCellStyle().apply {
                fillForegroundColor = IndexedColors.LIGHT_GREEN.index
                fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            }
            val nonConformeStyle = wb.createCellStyle().apply {
                fillForegroundColor = IndexedColors.ROSE.index
                fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            }
            val warnStyle = wb.createCellStyle().apply {
                fillForegroundColor = IndexedColors.LIGHT_YELLOW.index
                fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            }

            // Summary sheet
            val summary = wb.createSheet("Resume")
            val summaryRows = listOf(
                "Reference" to dossier.reference,
                "Type" to dossier.type.name,
                "Statut" to dossier.statut.name,
                "Fournisseur" to (dossier.fournisseur ?: ""),
                "Description" to (dossier.description ?: ""),
                "Montant TTC" to (dossier.montantTtc?.toString() ?: ""),
                "Montant HT" to (dossier.montantHt?.toString() ?: ""),
                "Montant TVA" to (dossier.montantTva?.toString() ?: ""),
                "Net a payer" to (dossier.montantNetAPayer?.toString() ?: ""),
                "Date creation" to dossier.dateCreation.format(dateFmt),
                "Date validation" to (dossier.dateValidation?.format(dateFmt) ?: ""),
                "Valide par" to (dossier.validePar ?: ""),
                "Motif rejet" to (dossier.motifRejet ?: "")
            )
            summaryRows.forEachIndexed { i, (k, v) ->
                val row = summary.createRow(i)
                row.createCell(0).apply {
                    setCellValue(k)
                    cellStyle = headerStyle
                }
                row.createCell(1).setCellValue(v)
            }
            summary.setColumnWidth(0, 6000)
            summary.setColumnWidth(1, 12000)

            // Validation sheet
            val validation = wb.createSheet("Controles")
            val headers = listOf("Regle", "Libelle", "Statut", "Detail", "Valeur attendue", "Valeur trouvee", "Commentaire")
            val headerRow = validation.createRow(0)
            headers.forEachIndexed { i, h ->
                headerRow.createCell(i).apply {
                    setCellValue(h)
                    cellStyle = headerStyle
                }
            }
            dossier.resultatsValidation.sortedBy { it.regle }.forEachIndexed { i, r ->
                val row = validation.createRow(i + 1)
                row.createCell(0).setCellValue(r.regle)
                row.createCell(1).setCellValue(r.libelle)
                row.createCell(2).setCellValue(r.statut.name)
                row.createCell(3).setCellValue(r.detail ?: "")
                row.createCell(4).setCellValue(r.valeurAttendue ?: "")
                row.createCell(5).setCellValue(r.valeurTrouvee ?: "")
                row.createCell(6).setCellValue(r.commentaire ?: "")
                val style = when (r.statut) {
                    StatutCheck.CONFORME -> conformeStyle
                    StatutCheck.NON_CONFORME -> nonConformeStyle
                    else -> warnStyle
                }
                for (c in 0..6) row.getCell(c).cellStyle = style
            }
            validation.setColumnWidth(0, 1800)
            validation.setColumnWidth(1, 9000)
            validation.setColumnWidth(2, 3500)
            validation.setColumnWidth(3, 12000)
            validation.setColumnWidth(4, 6000)
            validation.setColumnWidth(5, 6000)
            validation.setColumnWidth(6, 9000)

            // Documents sheet
            val docsSheet = wb.createSheet("Documents")
            val dHeaders = listOf("Nom fichier", "Type", "Statut extraction", "Date upload", "Confiance OCR", "Confiance extraction")
            val dHeaderRow = docsSheet.createRow(0)
            dHeaders.forEachIndexed { i, h ->
                dHeaderRow.createCell(i).apply {
                    setCellValue(h)
                    cellStyle = headerStyle
                }
            }
            dossier.documents.sortedBy { it.dateUpload }.forEachIndexed { i, d ->
                val row = docsSheet.createRow(i + 1)
                row.createCell(0).setCellValue(d.nomFichier)
                row.createCell(1).setCellValue(d.typeDocument.name)
                row.createCell(2).setCellValue(d.statutExtraction.name)
                row.createCell(3).setCellValue(d.dateUpload.format(dateFmt))
                row.createCell(4).setCellValue(if (d.ocrConfidence >= 0) "%.0f%%".format(d.ocrConfidence) else "")
                row.createCell(5).setCellValue(if (d.extractionConfidence >= 0) "%.0f%%".format(d.extractionConfidence * 100) else "")
            }
            docsSheet.setColumnWidth(0, 9000)
            docsSheet.setColumnWidth(1, 5000)
            docsSheet.setColumnWidth(2, 4000)
            docsSheet.setColumnWidth(3, 4500)

            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        }
    }
}
