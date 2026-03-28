package com.ocrsage.service

import com.ocrsage.dto.ExtractedLineItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import technology.tabula.ObjectExtractor
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm
import technology.tabula.extractors.BasicExtractionAlgorithm
import org.apache.pdfbox.pdmodel.PDDocument
import java.math.BigDecimal
import java.nio.file.Path

/**
 * Extracts line item tables from PDF invoices using Tabula-java.
 * Tabula is the industry standard for PDF table extraction (used by NYT, ProPublica, etc.)
 *
 * Strategy:
 * 1. Try SpreadsheetExtractionAlgorithm first (for PDFs with visible cell borders)
 * 2. Fallback to BasicExtractionAlgorithm (for PDFs without borders, uses column gaps)
 * 3. Map columns to line item fields by header detection
 */
@Service
class TableExtractionService {

    private val log = LoggerFactory.getLogger(javaClass)

    // Column header patterns for Moroccan invoices (French)
    private val descriptionHeaders = setOf("désignation", "designation", "description", "libellé", "libelle", "article", "produit", "service", "détail", "detail")
    private val quantityHeaders = setOf("quantité", "quantite", "qté", "qte", "qty", "quantity", "nombre", "nb")
    private val unitHeaders = setOf("unité", "unite", "unit", "u", "uom")
    private val unitPriceHeaders = setOf("prix unitaire", "p.u.", "pu", "prix u.", "prix ht", "unit price", "p.u.ht", "pu ht")
    private val tvaRateHeaders = setOf("taux tva", "tva %", "tva", "taux", "% tva", "rate")
    private val totalHtHeaders = setOf("montant ht", "total ht", "montant", "total", "amount")
    private val totalTtcHeaders = setOf("montant ttc", "total ttc", "net")

    fun extractLineItems(filePath: Path): List<ExtractedLineItem> {
        if (!filePath.toString().lowercase().endsWith(".pdf")) {
            log.info("Skipping table extraction: not a PDF file")
            return emptyList()
        }

        return try {
            PDDocument.load(filePath.toFile()).use { document ->
                val extractor = ObjectExtractor(document)
                val allItems = mutableListOf<ExtractedLineItem>()

                for (pageIndex in 0 until document.numberOfPages) {
                    val page = extractor.extract(pageIndex + 1)

                    // Try spreadsheet algorithm first (ruled tables)
                    var tables = SpreadsheetExtractionAlgorithm().extract(page)

                    // Fallback to basic algorithm (no ruled lines)
                    if (tables.isEmpty()) {
                        tables = BasicExtractionAlgorithm().extract(page)
                    }

                    for (table in tables) {
                        val rows = table.rows
                        if (rows.size < 2) continue // Need at least header + 1 data row

                        // Detect column mapping from header row
                        val headerRow = rows[0].map { it.text.trim().lowercase() }
                        val columnMap = detectColumnMapping(headerRow)

                        if (columnMap.isEmpty()) {
                            log.debug("No recognized columns in table header: {}", headerRow)
                            continue
                        }

                        log.info("Found invoice table with {} data rows, columns: {}", rows.size - 1, columnMap.keys)

                        // Extract data rows
                        for (rowIdx in 1 until rows.size) {
                            val cells = rows[rowIdx].map { it.text.trim() }
                            if (cells.all { it.isBlank() }) continue

                            // Skip summary rows (Total, Sous-total, etc.)
                            val firstCell = cells.firstOrNull()?.lowercase() ?: ""
                            if (firstCell.startsWith("total") || firstCell.startsWith("sous-total") ||
                                firstCell.startsWith("montant") || firstCell.startsWith("net ")) continue

                            val item = mapRowToLineItem(cells, columnMap, allItems.size + 1)
                            if (item != null) {
                                allItems.add(item)
                            }
                        }
                    }
                }

                log.info("Tabula extracted {} line items", allItems.size)
                allItems
            }
        } catch (e: Exception) {
            log.warn("Tabula table extraction failed: {}", e.message)
            emptyList()
        }
    }

    private fun detectColumnMapping(headers: List<String>): Map<String, Int> {
        val mapping = mutableMapOf<String, Int>()

        for ((index, header) in headers.withIndex()) {
            val h = header.replace(Regex("[^a-zéèêàùûôîï0-9\\s.%]"), "").trim()
            when {
                descriptionHeaders.any { h.contains(it) } -> mapping["description"] = index
                quantityHeaders.any { h.contains(it) } -> mapping["quantity"] = index
                unitHeaders.any { h == it } -> mapping["unit"] = index
                unitPriceHeaders.any { h.contains(it) } -> mapping["unit_price"] = index
                tvaRateHeaders.any { h.contains(it) } -> mapping["tva_rate"] = index
                totalTtcHeaders.any { h.contains(it) } -> mapping["total_ttc"] = index
                totalHtHeaders.any { h.contains(it) } -> mapping["total_ht"] = index
            }
        }

        return mapping
    }

    private fun mapRowToLineItem(cells: List<String>, columnMap: Map<String, Int>, lineNumber: Int): ExtractedLineItem? {
        fun cellAt(col: String): String? = columnMap[col]?.let { cells.getOrNull(it) }?.takeIf { it.isNotBlank() }

        val description = cellAt("description")
        val totalHt = parseAmount(cellAt("total_ht"))

        // At least description or an amount is needed
        if (description == null && totalHt == null) return null

        return ExtractedLineItem(
            lineNumber = lineNumber,
            description = description,
            quantity = parseAmount(cellAt("quantity")),
            unit = cellAt("unit"),
            unitPriceHt = parseAmount(cellAt("unit_price")),
            tvaRate = parseAmount(cellAt("tva_rate")),
            totalHt = totalHt,
            totalTtc = parseAmount(cellAt("total_ttc"))
        )
    }

    private fun parseAmount(str: String?): BigDecimal? {
        if (str.isNullOrBlank()) return null
        return try {
            val cleaned = str.trim()
            val hasCommaDecimal = cleaned.matches(Regex(""".*,\d{1,2}$"""))
            val normalized = if (hasCommaDecimal) {
                cleaned.replace("\\s".toRegex(), "").replace(".", "").replace(",", ".")
            } else {
                cleaned.replace("\\s".toRegex(), "").replace(",", "")
            }
            val value = BigDecimal(normalized.replace(Regex("[^\\d.]"), ""))
            if (value.compareTo(BigDecimal.ZERO) == 0) null else value
        } catch (_: NumberFormatException) {
            null
        }
    }
}
