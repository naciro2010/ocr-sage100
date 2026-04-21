package com.madaef.recondoc.dto.engagement

import com.madaef.recondoc.entity.engagement.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// === Requests ===

data class CreateEngagementRequest(
    @field:NotNull val type: TypeEngagement,
    @field:NotBlank val reference: String,
    val objet: String? = null,
    val fournisseur: String? = null,
    val montantHt: BigDecimal? = null,
    val montantTva: BigDecimal? = null,
    val tauxTva: BigDecimal? = null,
    val montantTtc: BigDecimal? = null,
    val dateDocument: LocalDate? = null,
    val dateSignature: LocalDate? = null,
    val dateNotification: LocalDate? = null,
    val statut: StatutEngagement? = null,

    // Specifiques Marche
    val numeroAo: String? = null,
    val dateAo: LocalDate? = null,
    val categorie: CategorieMarche? = null,
    val delaiExecutionMois: Int? = null,
    val penalitesRetardJourPct: BigDecimal? = null,
    val retenueGarantiePct: BigDecimal? = null,
    val cautionDefinitivePct: BigDecimal? = null,
    val revisionPrixAutorisee: Boolean? = null,

    // Specifiques BC
    val plafondMontant: BigDecimal? = null,
    val dateValiditeFin: LocalDate? = null,
    val seuilAntiFractionnement: BigDecimal? = null,

    // Specifiques Contrat
    val periodicite: PeriodiciteContrat? = null,
    val dateDebut: LocalDate? = null,
    val dateFin: LocalDate? = null,
    val reconductionTacite: Boolean? = null,
    val preavisResiliationJours: Int? = null,
    val indiceRevision: String? = null
)

data class UpdateEngagementRequest(
    val objet: String? = null,
    val fournisseur: String? = null,
    val montantHt: BigDecimal? = null,
    val montantTva: BigDecimal? = null,
    val tauxTva: BigDecimal? = null,
    val montantTtc: BigDecimal? = null,
    val dateDocument: LocalDate? = null,
    val dateSignature: LocalDate? = null,
    val dateNotification: LocalDate? = null,
    val statut: StatutEngagement? = null,

    val numeroAo: String? = null,
    val dateAo: LocalDate? = null,
    val categorie: CategorieMarche? = null,
    val delaiExecutionMois: Int? = null,
    val penalitesRetardJourPct: BigDecimal? = null,
    val retenueGarantiePct: BigDecimal? = null,
    val cautionDefinitivePct: BigDecimal? = null,
    val revisionPrixAutorisee: Boolean? = null,

    val plafondMontant: BigDecimal? = null,
    val dateValiditeFin: LocalDate? = null,
    val seuilAntiFractionnement: BigDecimal? = null,

    val periodicite: PeriodiciteContrat? = null,
    val dateDebut: LocalDate? = null,
    val dateFin: LocalDate? = null,
    val reconductionTacite: Boolean? = null,
    val preavisResiliationJours: Int? = null,
    val indiceRevision: String? = null
)

data class AttachDossierRequest(
    @field:NotNull val dossierId: UUID
)

// === Responses ===

data class EngagementListItem(
    val id: UUID,
    val type: TypeEngagement,
    val reference: String,
    val statut: StatutEngagement,
    val objet: String?,
    val fournisseur: String?,
    val montantTtc: BigDecimal?,
    val dateDocument: LocalDate?,
    val nbDossiers: Long,
    val montantConsomme: BigDecimal,
    val tauxConsommation: BigDecimal?
)

data class EngagementResponse(
    val id: UUID,
    val type: TypeEngagement,
    val reference: String,
    val statut: StatutEngagement,
    val objet: String?,
    val fournisseur: String?,
    val montantHt: BigDecimal?,
    val montantTva: BigDecimal?,
    val tauxTva: BigDecimal?,
    val montantTtc: BigDecimal?,
    val dateDocument: LocalDate?,
    val dateSignature: LocalDate?,
    val dateNotification: LocalDate?,
    val dateCreation: LocalDateTime,
    val dateModification: LocalDateTime?,

    // Specifiques (populated based on type)
    val marche: MarcheDetails? = null,
    val bonCommande: BonCommandeDetails? = null,
    val contrat: ContratDetails? = null,

    val dossiers: List<DossierAttache> = emptyList(),
    val montantConsomme: BigDecimal = BigDecimal.ZERO,
    val tauxConsommation: BigDecimal? = null
)

data class MarcheDetails(
    val numeroAo: String?,
    val dateAo: LocalDate?,
    val categorie: CategorieMarche?,
    val delaiExecutionMois: Int?,
    val penalitesRetardJourPct: BigDecimal?,
    val retenueGarantiePct: BigDecimal?,
    val cautionDefinitivePct: BigDecimal?,
    val revisionPrixAutorisee: Boolean
)

data class BonCommandeDetails(
    val plafondMontant: BigDecimal?,
    val dateValiditeFin: LocalDate?,
    val seuilAntiFractionnement: BigDecimal?
)

data class ContratDetails(
    val periodicite: PeriodiciteContrat?,
    val dateDebut: LocalDate?,
    val dateFin: LocalDate?,
    val reconductionTacite: Boolean,
    val preavisResiliationJours: Int?,
    val indiceRevision: String?
)

data class DossierAttache(
    val id: UUID,
    val reference: String,
    val statut: String,
    val fournisseur: String?,
    val montantTtc: BigDecimal?,
    val dateCreation: LocalDateTime
)

data class EngagementTreeNode(
    val id: UUID,
    val type: TypeEngagement,
    val reference: String,
    val objet: String?,
    val fournisseur: String?,
    val montantTtc: BigDecimal?,
    val statut: StatutEngagement,
    val dossiers: List<DossierAttache>
)

data class EngagementStats(
    val totalEngagements: Long,
    val actifs: Long,
    val clotures: Long,
    val suspendus: Long,
    val nbMarches: Long,
    val nbBonsCommande: Long,
    val nbContrats: Long,
    val montantTotalTtc: BigDecimal,
    val montantTotalConsomme: BigDecimal
)
