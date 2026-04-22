package com.madaef.recondoc.service

/**
 * Table de confusion OCR pour les champs 100% numeriques (ICE, RIB, IF).
 * Tesseract confond frequemment O/o avec 0, l/I avec 1. Utilise cote
 * TextNormalizationService (sur le texte brut, scope label) et cote
 * ExtractionSchemaValidator (sur une valeur isolee deja extraite).
 */
object OcrConfusions {

    fun applyDigitConfusions(s: String): String {
        if (s.isEmpty()) return s
        val buf = StringBuilder(s.length)
        for (c in s) {
            buf.append(when (c) {
                'O', 'o' -> '0'
                'l', 'I' -> '1'
                else -> c
            })
        }
        return buf.toString()
    }

    fun digitsOnlyWithConfusions(s: String): String =
        applyDigitConfusions(s).replace(NON_DIGITS, "")

    private val NON_DIGITS = Regex("[^\\d]")
}
