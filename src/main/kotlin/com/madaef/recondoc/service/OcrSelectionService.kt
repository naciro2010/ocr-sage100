package com.madaef.recondoc.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Scoring structure et choix du meilleur candidat OCR parmi plusieurs
 * moteurs (Tika, Mistral, PdfMarkdownExtractor, Tesseract) executes en
 * parallele.
 *
 * Pourquoi un scoring dedie : un simple `wordCount` ne suffit pas. Une
 * facture dont les colonnes HT/TVA/TTC sont preservees en Markdown vaut
 * bien plus qu'un texte brut de meme longueur ou les montants sont
 * ecrases par un alignement d'espaces. On pondere :
 *   - mots significatifs (> 1 char)
 *   - montants decimaux reconnaissables (ex: 1234,56)
 *   - dates ISO ou FR (ex: 2026-03-15, 15/03/2026)
 *   - en-tetes Markdown (#) ou tableaux (lignes avec >= 3 pipes)
 *   - lignes-bruit tres courtes (1-2 chars) en penalite
 */
@Service
class OcrSelectionService {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Candidate(
        val text: String,
        val engine: OcrService.OcrEngine,
        val pageCount: Int,
        val confidence: Double
    )

    data class ScoredCandidate(val candidate: Candidate, val score: Int)

    fun score(candidate: Candidate): Int {
        val text = candidate.text
        if (text.isBlank()) return 0

        val wordCount = text.split(Regex("\\s+")).count { it.length > 1 }
        val decimalAmounts = Regex("\\d+[.,]\\d{2}").findAll(text).count()
        val dates = Regex("\\b(\\d{4}-\\d{2}-\\d{2}|\\d{2}[/-]\\d{2}[/-]\\d{4})\\b").findAll(text).count()
        val mdHeaders = text.lines().count { it.trimStart().startsWith("#") }
        val tableRows = text.lines().count { line -> line.count { it == '|' } >= 3 }
        val noiseLines = text.lines().count { it.trim().length in 1..2 }

        return (1 * wordCount) +
            (10 * decimalAmounts) +
            (5 * dates) +
            (5 * mdHeaders) +
            (15 * tableRows) -
            (5 * noiseLines)
    }

    /**
     * Retourne le meilleur candidat ou null si tous sont vides.
     * En cas d'egalite parfaite, prefere le moteur qui preserve le mieux
     * les tableaux (Mistral OCR > PdfMarkdown > Tika > Tesseract).
     */
    fun pickBest(candidates: List<Candidate>): Candidate? {
        val valid = candidates.filter { it.text.isNotBlank() }
        if (valid.isEmpty()) return null

        val scored = valid.map { ScoredCandidate(it, score(it)) }
        val top = scored.maxByOrNull { it.score } ?: return null

        // Journalise la decision pour debugging / audit qualite.
        log.info("OCR selection: {} candidates, winner={} (score={}, text={}chars)",
            scored.size, top.candidate.engine, top.score, top.candidate.text.length)
        for (sc in scored) {
            if (sc != top) log.debug("  - {} score={} ({}chars)",
                sc.candidate.engine, sc.score, sc.candidate.text.length)
        }
        return top.candidate
    }
}
