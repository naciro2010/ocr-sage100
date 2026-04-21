package com.madaef.recondoc.service.validation.engagement

import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.ResultatValidation
import com.madaef.recondoc.entity.engagement.Engagement

/**
 * Strategie de validation engagement-level. Chaque sous-type d'engagement
 * (Marche / BC cadre / Contrat) a son propre validator avec ses regles
 * specifiques. Un validator commun joue les regles applicables aux 3 types.
 *
 * Implementations enregistrees comme @Service Spring ; le dispatcher
 * EngagementValidationDispatcher selectionne les validators applicables
 * au type runtime de l'engagement.
 */
interface EngagementValidator<T : Engagement> {
    /** Type runtime supporte par ce validator (null = toutes les sous-classes). */
    fun supports(): Class<T>

    /** Codes des regles portees par ce validator (ex. R-M01..R-M07). */
    fun rules(): List<String>

    /**
     * Execute les regles de ce validator pour un dossier rattache a un engagement.
     * Retourne une liste de ResultatValidation liee au dossier (la persistence
     * est assuree par le dispatcher).
     */
    fun validate(engagement: T, dossier: DossierPaiement, context: EngagementValidationContext): List<ResultatValidation>
}

/**
 * Contexte partage entre validators : informations pre-calculees pour eviter
 * les doublons d'acces DB entre regles.
 */
data class EngagementValidationContext(
    val montantConsomme: java.math.BigDecimal,
    val dossiersRattaches: List<DossierPaiement>,
    val toleranceMontant: java.math.BigDecimal
)
