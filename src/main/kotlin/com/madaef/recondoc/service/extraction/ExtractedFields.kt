package com.madaef.recondoc.service.extraction

/**
 * Claude renvoie parfois des cles avec une casse legerement differente de celle
 * du schema (ex: `Ice` vs `ice`, `MontantTTC` vs `montantTTC`). Cet accesseur
 * resout le champ par nom exact puis en mode insensible a la casse, pour eviter
 * des faux "champ manquant" qui declencheraient des re-extractions inutiles.
 */
internal fun Map<String, Any?>.getFieldCaseInsensitive(name: String): Any? =
    this[name] ?: this.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
