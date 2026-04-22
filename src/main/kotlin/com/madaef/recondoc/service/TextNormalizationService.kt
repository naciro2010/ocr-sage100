package com.madaef.recondoc.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Normalise le texte brut OCR avant extraction regex.
 * Corrige les artefacts courants de Tesseract sur les factures.
 */
@Service
class TextNormalizationService {

    private val log = LoggerFactory.getLogger(javaClass)

    fun normalize(raw: String): String {
        var text = raw

        // 1. Normaliser les fins de ligne
        text = text.replace("\r\n", "\n").replace("\r", "\n")

        // 2. Supprimer les lignes vides excessives (max 2 consecutives)
        text = text.replace(Regex("\n{3,}"), "\n\n")

        // 3. Corriger les espaces parasites dans les nombres (Tesseract separe souvent les chiffres)
        // "1 234 567,89" reste intact, mais "1 2 3 4,5 6" -> "1234,56"
        // On cible specifiquement les contextes de montants
        text = fixAmountSpaces(text)

        // 4. Normaliser les separateurs decimaux ambigus
        // "1.234.567,89" -> on garde (format FR correct)
        // "1,234,567.89" -> on garde (format EN correct)

        // 5. Corriger les substitutions OCR courantes dans les contextes numeriques
        text = fixOcrDigitSubstitutions(text)

        // 6. Normaliser les labels courants mal OCR-ises
        text = fixOcrLabelSubstitutions(text)

        // 7. Normalize Arabic text (common in Moroccan documents)
        text = normalizeArabic(text)

        // 8. Supprimer les caracteres de controle et zero-width
        text = text.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\u200B\\u200C\\u200D\\uFEFF]"), "")

        // 9. Normaliser les espaces multiples en un seul (sauf en debut de ligne)
        text = text.replace(Regex("(?<=\\S) {2,}(?=\\S)"), " ")

        return text.trim()
    }

    /**
     * Normalise le texte arabe : kashidas, hamzas, tatweel, diacritiques optionnels.
     */
    private fun normalizeArabic(text: String): String {
        var result = text
        // Remove excessive kashida/tatweel (decorative elongation)
        result = result.replace(Regex("\u0640{2,}"), "\u0640")
        // Normalize alef variants to plain alef
        result = result.replace('\u0622', '\u0627') // Alef madda -> Alef
        result = result.replace('\u0623', '\u0627') // Alef hamza above -> Alef
        result = result.replace('\u0625', '\u0627') // Alef hamza below -> Alef
        // Remove optional diacritics (tashkeel) that OCR often misplaces
        result = result.replace(Regex("[\u064B-\u065F\u0670]"), "")
        return result
    }

    /**
     * Corrige les espaces parasites inseres par Tesseract dans les montants.
     * Ex: "1 2 3 4 , 5 6" -> "1234,56" dans un contexte de montant
     */
    private fun fixAmountSpaces(text: String): String {
        // Pattern: un label de montant suivi de chiffres espaces
        val amountContextPattern = Regex(
            """((?:HT|TTC|TVA|Total|Montant|Net|Sous.?total|Remise|Base)\s*[:.]?\s*)([\d\s]+[.,]\s*\d{1,2})""",
            RegexOption.IGNORE_CASE
        )
        return amountContextPattern.replace(text) { match ->
            val label = match.groupValues[1]
            val amount = match.groupValues[2].replace(Regex("\\s+"), "")
            "$label$amount"
        }
    }

    /**
     * Dans les contextes numeriques (pres de labels fiscaux / montants),
     * corrige O->0, l->1, I->1 sur les caracteres qui suivent le label.
     * La table de confusion vit dans OcrConfusions pour etre reutilisee
     * par ExtractionSchemaValidator sur les valeurs deja extraites.
     */
    private fun fixOcrDigitSubstitutions(text: String): String {
        var result = text
        result = ICE_LABEL_RE.replace(result, ::applyConfusionsToCapture)
        result = IF_LABEL_RE.replace(result, ::applyConfusionsToCapture)
        result = RIB_LABEL_RE.replace(result, ::applyConfusionsToCapture)
        return result
    }

    private fun applyConfusionsToCapture(match: MatchResult): String =
        match.groupValues[1] + OcrConfusions.applyDigitConfusions(match.groupValues[2])

    companion object {
        private val ICE_LABEL_RE = Regex(
            """(I\.?C\.?E\.?\s*[:.]?\s*)([0-9OolI]{13,17})""", RegexOption.IGNORE_CASE
        )
        private val IF_LABEL_RE = Regex(
            """(I\.?F\.?\s*[:.]?\s*)([0-9OolI]{6,10})""", RegexOption.IGNORE_CASE
        )
        private val RIB_LABEL_RE = Regex(
            """(R\.?I\.?B\.?\s*[:.]?\s*)([0-9OolI\s]{24,35})""", RegexOption.IGNORE_CASE
        )
    }

    /**
     * Corrige les labels de facture courants mal reconnus par Tesseract.
     */
    private fun fixOcrLabelSubstitutions(text: String): String {
        var result = text

        // "Factura" (OCR espagnol) -> "Facture"
        result = result.replace(Regex("\\bFactura\\b", RegexOption.IGNORE_CASE), "Facture")

        // "T.V.A" avec points manquants
        result = result.replace(Regex("\\bTVA\\b"), "T.V.A.")

        // "l.C.E" -> "I.C.E"
        result = result.replace(Regex("\\bl\\.C\\.E", RegexOption.IGNORE_CASE), "I.C.E")

        // "RlB" -> "RIB"
        result = result.replace(Regex("\\bRlB\\b"), "RIB")

        return result
    }
}
