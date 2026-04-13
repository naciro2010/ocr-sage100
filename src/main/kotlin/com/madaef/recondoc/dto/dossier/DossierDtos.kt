package com.madaef.recondoc.dto.dossier

import com.madaef.recondoc.entity.dossier.*
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// === Request DTOs ===

data class CreateDossierRequest(
    @field:NotNull val type: DossierType,
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
    @field:NotNull val statut: StatutDossier,
    val motifRejet: String? = null,
    val validePar: String? = null
)

data class DashboardStatsResponse(
    val total: Long,
    val brouillons: Long,
    val enVerification: Long,
    val valides: Long,
    val rejetes: Long,
    val montantTotal: BigDecimal
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
    val factures: List<Map<String, Any?>> = emptyList(),
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
    val id: String?,
    val regle: String,
    val libelle: String,
    val statut: StatutCheck,
    val detail: String?,
    val valeurAttendue: String?,
    val valeurTrouvee: String?,
    val source: String,
    val commentaire: String?,
    val corrigePar: String?,
    val statutOriginal: String?,
    val documentIds: List<String>? = null
)

data class AuditLogResponse(
    val action: String,
    val detail: String?,
    val utilisateur: String?,
    val dateAction: LocalDateTime
)

// === Mapper functions ===

fun DossierPaiement.toListResponse(): DossierListResponse {
    val docs = try { documents.size } catch (_: Exception) { 0 }
    val checksConformes = try { resultatsValidation.count { it.statut == StatutCheck.CONFORME } } catch (_: Exception) { 0 }
    val checksTotal = try { resultatsValidation.size } catch (_: Exception) { 0 }
    return DossierListResponse(
        id = id!!, reference = reference, type = type, statut = statut,
        fournisseur = fournisseur, description = description,
        montantTtc = montantTtc, montantNetAPayer = montantNetAPayer,
        dateCreation = dateCreation,
        nbDocuments = docs,
        nbChecksConformes = checksConformes,
        nbChecksTotal = checksTotal
    )
}

fun Document.toResponse(): DocumentResponse = DocumentResponse(
    id = id!!, typeDocument = typeDocument, nomFichier = nomFichier,
    statutExtraction = statutExtraction, erreurExtraction = erreurExtraction,
    dateUpload = dateUpload, donneesExtraites = donneesExtraites
)

fun ResultatValidation.toResponse(): ValidationResultResponse = ValidationResultResponse(
    id = id?.toString(), regle = regle, libelle = libelle, statut = statut,
    detail = detail, valeurAttendue = valeurAttendue, valeurTrouvee = valeurTrouvee,
    source = source, commentaire = commentaire, corrigePar = corrigePar,
    statutOriginal = statutOriginal,
    documentIds = documentIds?.split(",")?.filter { it.isNotBlank() }
)
