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
     * corrige O->0, l->1, I->1, S->5, B->8
     */
    private fun fixOcrDigitSubstitutions(text: String): String {
        // Corriger ICE avec lettres parasites: "ICE: 0O12345678901234" -> digits only
        var result = text

        // ICE: corriger les O en 0 et l/I en 1 dans les 15 caracteres apres "ICE"
        result = Regex("""(I\.?C\.?E\.?\s*[:.]?\s*)([0-9OolI]{13,17})""", RegexOption.IGNORE_CASE)
            .replace(result) { match ->
                val label = match.groupValues[1]
                val digits = match.groupValues[2]
                    .replace('O', '0').replace('o', '0')
                    .replace('l', '1').replace('I', '1')
                "$label$digits"
            }

        // IF: meme correction
        result = Regex("""(I\.?F\.?\s*[:.]?\s*)([0-9OolI]{6,10})""", RegexOption.IGNORE_CASE)
            .replace(result) { match ->
                val label = match.groupValues[1]
                val digits = match.groupValues[2]
                    .replace('O', '0').replace('o', '0')
                    .replace('l', '1').replace('I', '1')
                "$label$digits"
            }

        // RIB: corriger dans les 24 caracteres
        result = Regex("""(R\.?I\.?B\.?\s*[:.]?\s*)([0-9OolI\s]{24,35})""", RegexOption.IGNORE_CASE)
            .replace(result) { match ->
                val label = match.groupValues[1]
                val digits = match.groupValues[2]
                    .replace('O', '0').replace('o', '0')
                    .replace('l', '1').replace('I', '1')
                "$label$digits"
            }

        return result
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
