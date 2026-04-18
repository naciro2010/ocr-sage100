package com.madaef.recondoc

import com.madaef.recondoc.service.PdfMarkdownExtractor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfMarkdownExtractorTest {

    private val extractor = PdfMarkdownExtractor()

    @Test
    fun `splitByWideGaps splits on 2+ spaces and tabs`() {
        assertEquals(listOf("HT", "TVA", "TTC"), extractor.splitByWideGaps("HT   TVA    TTC"))
        assertEquals(listOf("A", "B"), extractor.splitByWideGaps("A\tB"))
        assertEquals(listOf("Single line of prose"), extractor.splitByWideGaps("Single line of prose"))
        assertEquals(emptyList(), extractor.splitByWideGaps("   "))
    }

    @Test
    fun `columnar block with 3 rows is rendered as Markdown table`() {
        val input = """
            Facture N 123
            Designation    HT    TVA    TTC
            Prestation A   1000  200   1200
            Prestation B   500   100   600
            Net a payer : 1800
        """.trimIndent()

        val (md, tableCount) = extractor.convertColumnarBlocksToMarkdown(input)

        assertEquals(1, tableCount)
        assertTrue(md.contains("| Designation | HT | TVA | TTC |"), "Header row missing in:\n$md")
        assertTrue(md.contains("| Prestation A | 1000 | 200 | 1200 |"), "Data row missing in:\n$md")
        assertTrue(md.contains("| --- |"), "Separator missing in:\n$md")
        // Non-table text is preserved
        assertTrue(md.contains("Facture N 123"))
        assertTrue(md.contains("Net a payer : 1800"))
    }

    @Test
    fun `single columnar line is not rendered as a table`() {
        val input = """
            Entete du document
            Numero : F-123    Date : 2026-01-15
            Client : MADAEF SA
        """.trimIndent()

        val (md, tableCount) = extractor.convertColumnarBlocksToMarkdown(input)

        // Only one 2-column line in a row: not enough to form a table.
        assertEquals(0, tableCount)
        assertFalse(md.contains("| --- |"), "Unexpected table in:\n$md")
    }

    @Test
    fun `pipe characters in cells are escaped`() {
        val input = """
            Code    Libelle
            A|B     Test cell
            C|D     Another
        """.trimIndent()

        val (md, tableCount) = extractor.convertColumnarBlocksToMarkdown(input)

        assertEquals(1, tableCount)
        assertTrue(md.contains("A\\|B"), "Pipe not escaped in:\n$md")
        assertTrue(md.contains("C\\|D"), "Pipe not escaped in:\n$md")
    }

    @Test
    fun `extract returns null for non-pdf paths`() {
        val path = java.nio.file.Path.of("/tmp/not-a-pdf.txt")
        assertEquals(null, extractor.extract(path))
    }
}
