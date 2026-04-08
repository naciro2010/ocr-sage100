package com.ocrsage.repository.dossier

import com.ocrsage.entity.dossier.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DossierRepository : JpaRepository<DossierPaiement, UUID> {
    fun findByStatut(statut: StatutDossier): List<DossierPaiement>
    fun findByType(type: DossierType): List<DossierPaiement>
    fun countByStatut(statut: StatutDossier): Long
}

interface DocumentRepository : JpaRepository<Document, UUID> {
    fun findByDossierId(dossierId: UUID): List<Document>
    fun findByDossierIdAndTypeDocument(dossierId: UUID, type: TypeDocument): Document?
}

interface FactureRepository : JpaRepository<Facture, UUID> {
    fun findByDossierId(dossierId: UUID): Facture?
}

interface BonCommandeRepository : JpaRepository<BonCommande, UUID> {
    fun findByDossierId(dossierId: UUID): BonCommande?
}

interface ContratAvenantRepository : JpaRepository<ContratAvenant, UUID> {
    fun findByDossierId(dossierId: UUID): ContratAvenant?
}

interface OrdrePaiementRepository : JpaRepository<OrdrePaiement, UUID> {
    fun findByDossierId(dossierId: UUID): OrdrePaiement?
}

interface ChecklistAutocontroleRepository : JpaRepository<ChecklistAutocontrole, UUID> {
    fun findByDossierId(dossierId: UUID): ChecklistAutocontrole?
}

interface TableauControleRepository : JpaRepository<TableauControle, UUID> {
    fun findByDossierId(dossierId: UUID): TableauControle?
}

interface PvReceptionRepository : JpaRepository<PvReception, UUID> {
    fun findByDossierId(dossierId: UUID): PvReception?
}

interface AttestationFiscaleRepository : JpaRepository<AttestationFiscale, UUID> {
    fun findByDossierId(dossierId: UUID): AttestationFiscale?
}

interface ResultatValidationRepository : JpaRepository<ResultatValidation, UUID> {
    fun findByDossierId(dossierId: UUID): List<ResultatValidation>
    fun deleteByDossierId(dossierId: UUID)
}
