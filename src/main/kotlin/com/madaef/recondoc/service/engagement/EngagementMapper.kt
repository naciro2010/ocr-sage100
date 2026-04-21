package com.madaef.recondoc.service.engagement

import com.madaef.recondoc.dto.engagement.*
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.engagement.*
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Mapper pur entites -> DTOs. Pas de dependance DB : les montants consommes
 * et le taux de consommation sont passes en parametre, calcules une seule
 * fois par le service (evite les N+1).
 */
@Component
class EngagementMapper {

    fun toResponse(
        engagement: Engagement,
        montantConsomme: BigDecimal,
        dossiers: List<DossierPaiement>
    ): EngagementResponse = EngagementResponse(
        id = engagement.id!!,
        type = engagement.typeEngagement(),
        reference = engagement.reference,
        statut = engagement.statut,
        objet = engagement.objet,
        fournisseur = engagement.fournisseur,
        montantHt = engagement.montantHt,
        montantTva = engagement.montantTva,
        tauxTva = engagement.tauxTva,
        montantTtc = engagement.montantTtc,
        dateDocument = engagement.dateDocument,
        dateSignature = engagement.dateSignature,
        dateNotification = engagement.dateNotification,
        dateCreation = engagement.dateCreation,
        dateModification = engagement.dateModification,
        marche = (engagement as? EngagementMarche)?.toDetails(),
        bonCommande = (engagement as? EngagementBonCommande)?.toDetails(),
        contrat = (engagement as? EngagementContrat)?.toDetails(),
        dossiers = dossiers.map(::toDossierAttache),
        montantConsomme = montantConsomme,
        tauxConsommation = tauxConsommation(engagement.montantTtc, montantConsomme)
    )

    fun toListItem(
        engagement: Engagement,
        montantConsomme: BigDecimal,
        nbDossiers: Long
    ): EngagementListItem = EngagementListItem(
        id = engagement.id!!,
        type = engagement.typeEngagement(),
        reference = engagement.reference,
        statut = engagement.statut,
        objet = engagement.objet,
        fournisseur = engagement.fournisseur,
        montantTtc = engagement.montantTtc,
        dateDocument = engagement.dateDocument,
        nbDossiers = nbDossiers,
        montantConsomme = montantConsomme,
        tauxConsommation = tauxConsommation(engagement.montantTtc, montantConsomme)
    )

    fun toTreeNode(engagement: Engagement, dossiers: List<DossierPaiement>): EngagementTreeNode =
        EngagementTreeNode(
            id = engagement.id!!,
            type = engagement.typeEngagement(),
            reference = engagement.reference,
            objet = engagement.objet,
            fournisseur = engagement.fournisseur,
            montantTtc = engagement.montantTtc,
            statut = engagement.statut,
            dossiers = dossiers.map(::toDossierAttache)
        )

    fun toDossierAttache(dossier: DossierPaiement) = DossierAttache(
        id = dossier.id!!,
        reference = dossier.reference,
        statut = dossier.statut.name,
        fournisseur = dossier.fournisseur,
        montantTtc = dossier.montantTtc,
        dateCreation = dossier.dateCreation
    )

    private fun tauxConsommation(plafond: BigDecimal?, consomme: BigDecimal): BigDecimal? =
        plafond?.takeIf { it > BigDecimal.ZERO }?.let {
            consomme.divide(it, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
        }

    private fun EngagementMarche.toDetails() = MarcheDetails(
        numeroAo = numeroAo,
        dateAo = dateAo,
        categorie = categorie,
        delaiExecutionMois = delaiExecutionMois,
        penalitesRetardJourPct = penalitesRetardJourPct,
        retenueGarantiePct = retenueGarantiePct,
        cautionDefinitivePct = cautionDefinitivePct,
        revisionPrixAutorisee = revisionPrixAutorisee
    )

    private fun EngagementBonCommande.toDetails() = BonCommandeDetails(
        plafondMontant = plafondMontant,
        dateValiditeFin = dateValiditeFin,
        seuilAntiFractionnement = seuilAntiFractionnement
    )

    private fun EngagementContrat.toDetails() = ContratDetails(
        periodicite = periodicite,
        dateDebut = dateDebut,
        dateFin = dateFin,
        reconductionTacite = reconductionTacite,
        preavisResiliationJours = preavisResiliationJours,
        indiceRevision = indiceRevision
    )
}
