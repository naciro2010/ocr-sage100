package com.madaef.recondoc.service

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Extraction PDF -> Markdown pour les PDF numeriques (textuels).
 *
 * Pourquoi : Tika retourne un texte plat ou les tableaux (factures, BC,
 * grilles tarifaires) deviennent des colonnes alignees par des espaces.
 * Le LLM interprete mieux un tableau Markdown (| col1 | col2 |) qu'un
 * alignement visuel perdu apres OCR.
 *
 * Strategie :
 *  - PDFBox extrait le texte page par page avec preservation des positions.
 *  - Une heuristique detecte les blocs colonnaires (>= 2 lignes contigues
 *    avec un nombre de colonnes coherent) et les rend en tables Markdown.
 *  - Le reste du texte est preserve tel quel.
 *
 * N'est applique qu'aux PDF "digital" (Tika a deja trouve du texte riche) ;
 * aucun effet sur les PDF scannes (qui passent par OCR).
 */
@Service
class PdfMarkdownExtractor {

    private val log = LoggerFactory.getLogger(javaClass)

    data class MarkdownResult(
        val markdown: String,
        val pageCount: Int,
        val tableCount: Int
    )

    fun extract(filePath: Path): MarkdownResult? {
        if (!filePath.toString().lowercase().endsWith(".pdf")) return null
        return try {
            Loader.loadPDF(filePath.toFile()).use { doc ->
                val pageCount = doc.pages.count
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    lineSeparator = "\n"
                    paragraphStart = ""
                    paragraphEnd = ""
                    wordSeparator = " "
                }

                val sb = StringBuilder()
                var totalTables = 0
                for (pageIdx in 1..pageCount) {
                    stripper.startPage = pageIdx
                    stripper.endPage = pageIdx
                    val pageText = stripper.getText(doc).trimEnd()
                    if (pageText.isBlank()) continue

                    if (pageCount > 1) sb.append("## Page ").append(pageIdx).append("\n\n")
                    val (markdown, tables) = convertColumnarBlocksToMarkdown(pageText)
                    totalTables += tables
                    sb.append(markdown).append("\n\n")
                }
                MarkdownResult(sb.toString().trim(), pageCount, totalTables)
            }
        } catch (e: Exception) {
            log.warn("PDF markdown extraction failed for {}: {}", filePath, e.message)
            null
        }
    }

    /**
     * Convertit les blocs de lignes colonnaires en tables Markdown.
     * Une "table" est un groupe contigu de >= 2 lignes dont le nombre de
     * colonnes (separees par >=2 espaces ou une tabulation) correspond
     * a la premiere ligne du bloc (+/- 1).
     *
     * Retourne (markdown, nombre de tables detectees).
     */
    internal fun convertColumnarBlocksToMarkdown(pageText: String): Pair<String, Int> {
        val lines = pageText.split("\n")
        val output = StringBuilder()
        var tableCount = 0
        var i = 0
        while (i < lines.size) {
            val cols = splitByWideGaps(lines[i])
            if (cols.size >= 2) {
                val block = mutableListOf(cols)
                val expected = cols.size
                var j = i + 1
                while (j < lines.size) {
                    val nextLine = lines[j]
                    if (nextLine.isBlank()) break
                    val nextCols = splitByWideGaps(nextLine)
                    if (nextCols.size >= 2 && nextCols.size in (expected - 1)..(expected + 1)) {
                        block += nextCols
                        j++
                    } else break
                }
                if (block.size >= 2) {
                    output.append(renderMarkdownTable(block)).append("\n")
                    tableCount++
                    i = j
                    continue
                }
            }
            output.append(lines[i]).append("\n")
            i++
        }
        return output.toString().trimEnd() to tableCount
    }

    /**
     * Decoupe une ligne par les gaps d'espaces larges (>=2) ou tabulations.
     * Les gaps courts (espaces simples) sont conserves dans la colonne.
     */
    internal fun splitByWideGaps(line: String): List<String> {
        if (line.isBlank()) return emptyList()
        return line.trim()
            .split(Regex(" {2,}|\t+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun renderMarkdownTable(rows: List<List<String>>): String {
        val maxCols = rows.maxOf { it.size }
        val padded = rows.map { row ->
            if (row.size < maxCols) row + List(maxCols - row.size) { "" } else row
        }
        val sb = StringBuilder()
        sb.append("| ").append(padded[0].joinToString(" | ") { escape(it) }).append(" |\n")
        sb.append("|").append(" --- |".repeat(maxCols)).append("\n")
        for (r in 1 until padded.size) {
            sb.append("| ").append(padded[r].joinToString(" | ") { escape(it) }).append(" |\n")
        }
        return sb.toString()
    }

    private fun escape(s: String): String = s.replace("|", "\\|")
}
