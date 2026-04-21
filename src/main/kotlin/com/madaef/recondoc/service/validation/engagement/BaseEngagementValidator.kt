package com.madaef.recondoc.service.validation.engagement

import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.ResultatValidation
import com.madaef.recondoc.entity.dossier.StatutCheck
import com.madaef.recondoc.entity.engagement.Engagement

/**
 * Classe de base des validators d'engagement. Centralise :
 * - la source "ENGAGEMENT" (toutes les regles produites par cette couche)
 * - le helper na() pour les cas NON_APPLICABLE (donnees manquantes)
 * - le helper result() pour construire un ResultatValidation coherent
 *
 * Chaque sous-classe se concentre uniquement sur la logique metier
 * des regles qu'elle porte (principe SRP).
 */
abstract class BaseEngagementValidator<T : Engagement> : EngagementValidator<T> {

    companion object {
        const val SOURCE = "ENGAGEMENT"
    }

    /** Cas non applicable : donnees insuffisantes pour evaluer la regle. */
    protected fun na(code: String, libelle: String, dossier: DossierPaiement, raison: String): ResultatValidation =
        ResultatValidation(
            dossier = dossier,
            regle = code,
            libelle = libelle,
            statut = StatutCheck.NON_APPLICABLE,
            detail = raison,
            source = SOURCE
        )

    /** Construit un ResultatValidation avec la source ENGAGEMENT par defaut. */
    protected fun result(
        code: String,
        libelle: String,
        dossier: DossierPaiement,
        statut: StatutCheck,
        detail: String? = null,
        attendu: String? = null,
        trouve: String? = null
    ): ResultatValidation = ResultatValidation(
        dossier = dossier,
        regle = code,
        libelle = libelle,
        statut = statut,
        detail = detail,
        valeurAttendue = attendu,
        valeurTrouvee = trouve,
        source = SOURCE
    )
}
