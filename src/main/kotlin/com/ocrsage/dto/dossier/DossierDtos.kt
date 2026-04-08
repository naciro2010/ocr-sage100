package com.ocrsage.dto.dossier

import com.ocrsage.entity.dossier.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// === Request DTOs ===

data class CreateDossierRequest(
    val type: DossierType,
    val fournisseur: String? = null,
    val description: String? = null
)

data class UpdateDossierRequest(
    val fournisseur: String? = null,
    val description: String? = null,
    val montantTtc: BigDecimal? = null,
    val montantHt: BigDecimal? = null,
    val montantTva: BigDecimal? = null,
    val montantNetAPayer: BigDecimal? = null
)

data class ChangeStatutRequest(
    val statut: StatutDossier,
    val motifRejet: String? = null,
    val validePar: String? = null
)

// === Response DTOs ===

data class DossierResponse(
    val id: UUID,
    val reference: String,
    val type: DossierType,
    val statut: StatutDossier,
    val fournisseur: String?,
    val description: String?,
    val montantTtc: BigDecimal?,
    val montantHt: BigDecimal?,
    val montantTva: BigDecimal?,
    val montantNetAPayer: BigDecimal?,
    val dateCreation: LocalDateTime,
    val dateValidation: LocalDateTime?,
    val validePar: String?,
    val motifRejet: String?,
    val documents: List<DocumentResponse>,
    val facture: Map<String, Any?>?,
    val bonCommande: Map<String, Any?>?,
    val contratAvenant: Map<String, Any?>?,
    val ordrePaiement: Map<String, Any?>?,
    val checklistAutocontrole: Map<String, Any?>?,
    val tableauControle: Map<String, Any?>?,
    val pvReception: Map<String, Any?>?,
    val attestationFiscale: Map<String, Any?>?,
    val resultatsValidation: List<ValidationResultResponse>
)

data class DossierListResponse(
    val id: UUID,
    val reference: String,
    val type: DossierType,
    val statut: StatutDossier,
    val fournisseur: String?,
    val description: String?,
    val montantTtc: BigDecimal?,
    val montantNetAPayer: BigDecimal?,
    val dateCreation: LocalDateTime,
    val nbDocuments: Int,
    val nbChecksConformes: Int,
    val nbChecksTotal: Int
)

data class DocumentResponse(
    val id: UUID,
    val typeDocument: TypeDocument,
    val nomFichier: String,
    val statutExtraction: StatutExtraction,
    val erreurExtraction: String?,
    val dateUpload: LocalDateTime,
    val donneesExtraites: Map<String, Any?>?
)

data class ValidationResultResponse(
    val regle: String,
    val libelle: String,
    val statut: StatutCheck,
    val detail: String?,
    val valeurAttendue: String?,
    val valeurTrouvee: String?,
    val source: String
)

// === Mapper functions ===

fun DossierPaiement.toListResponse(): DossierListResponse = DossierListResponse(
    id = id!!, reference = reference, type = type, statut = statut,
    fournisseur = fournisseur, description = description,
    montantTtc = montantTtc, montantNetAPayer = montantNetAPayer,
    dateCreation = dateCreation,
    nbDocuments = documents.size,
    nbChecksConformes = resultatsValidation.count { it.statut == StatutCheck.CONFORME },
    nbChecksTotal = resultatsValidation.size
)

fun Document.toResponse(): DocumentResponse = DocumentResponse(
    id = id!!, typeDocument = typeDocument, nomFichier = nomFichier,
    statutExtraction = statutExtraction, erreurExtraction = erreurExtraction,
    dateUpload = dateUpload, donneesExtraites = donneesExtraites
)

fun ResultatValidation.toResponse(): ValidationResultResponse = ValidationResultResponse(
    regle = regle, libelle = libelle, statut = statut,
    detail = detail, valeurAttendue = valeurAttendue, valeurTrouvee = valeurTrouvee,
    source = source
)
