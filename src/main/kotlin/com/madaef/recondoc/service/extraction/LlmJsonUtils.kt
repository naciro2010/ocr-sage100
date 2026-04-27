package com.madaef.recondoc.service.extraction

/**
 * Helpers de parsing pour les reponses Claude en mode texte libre.
 *
 * En mode `tool_use` la reponse est deja un objet JSON parsable directement.
 * Le mode texte libre subsiste comme fallback (cf. `DossierService`) et
 * Claude peut alors entourer le JSON d'explications. L'ancien code utilisait
 * `Regex("\\{[\\s\\S]*\\}")` greedy : si la reponse contenait DEUX objets
 * JSON encadres de texte, le regex matchait du PREMIER `{` jusqu'au DERNIER
 * `}`, donc tout le texte intermediaire — pas un JSON valide.
 */
object LlmJsonUtils {

    /**
     * Trouve le PREMIER objet JSON syntaxiquement equilibre dans `text`.
     * Compte les accolades en respectant les strings JSON (`"..."`) et les
     * sequences d'echappement (`\`, `"`). Retourne `null` si aucun objet
     * complet n'est present.
     */
    fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            if (inString) {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
