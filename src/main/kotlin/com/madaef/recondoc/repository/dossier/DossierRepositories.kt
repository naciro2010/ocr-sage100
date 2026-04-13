package com.madaef.recondoc.repository.dossier

import com.madaef.recondoc.entity.dossier.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface DossierRepository : JpaRepository<DossierPaiement, UUID> {
    fun findByStatut(statut: StatutDossier): List<DossierPaiement>
    fun findByType(type: DossierType): List<DossierPaiement>
    fun countByStatut(statut: StatutDossier): Long

    @EntityGraph(attributePaths = [
        "documents", "factures", "bonCommande", "contratAvenant",
        "ordrePaiement", "checklistAutocontrole",
        "tableauControle", "pvReception", "attestationFiscale",
        "resultatsValidation"
    ])
    @Query("SELECT d FROM DossierPaiement d WHERE d.id = :id")
    fun findByIdWithAll(id: UUID): Optional<DossierPaiement>

    @Query("""
        SELECT d.id, d.reference, d.type, d.statut, d.fournisseur, d.description,
               d.montantTtc, d.montantHt, d.montantTva, d.montantNetAPayer,
               d.dateCreation, d.dateValidation, d.validePar, d.motifRejet,
               (SELECT COUNT(doc) FROM Document doc WHERE doc.dossier.id = d.id),
               (SELECT COUNT(rv) FROM ResultatValidation rv WHERE rv.dossier.id = d.id AND rv.statut = 'CONFORME'),
               (SELECT COUNT(rv) FROM ResultatValidation rv WHERE rv.dossier.id = d.id)
        FROM DossierPaiement d WHERE d.id = :id
    """)
    fun findSummaryById(id: UUID): Array<Any>?

    @Query("""
        SELECT d.statut AS statut, COUNT(d) AS cnt, COALESCE(SUM(d.montantTtc), 0) AS total
        FROM DossierPaiement d GROUP BY d.statut
    """)
    fun getStatsByStatut(): List<Array<Any>>

    @Query("""
        SELECT d.id, d.reference, d.type, d.statut, d.fournisseur, d.description,
               d.montantTtc, d.montantNetAPayer, d.dateCreation,
               COUNT(DISTINCT doc.id),
               COUNT(DISTINCT CASE WHEN rv.statut = 'CONFORME' THEN rv.id END),
               COUNT(DISTINCT rv.id)
        FROM DossierPaiement d
        LEFT JOIN d.documents doc
        LEFT JOIN d.resultatsValidation rv
        WHERE (:statut IS NULL OR d.statut = :statut)
        AND (:type IS NULL OR d.type = :type)
        AND (:fournisseur IS NULL OR LOWER(d.fournisseur) LIKE LOWER(CONCAT('%', :fournisseur, '%')))
        GROUP BY d.id, d.reference, d.type, d.statut, d.fournisseur, d.description,
                 d.montantTtc, d.montantNetAPayer, d.dateCreation
    """)
    fun searchProjected(statut: StatutDossier?, type: DossierType?, fournisseur: String?, pageable: Pageable): Page<Array<Any>>

    @Query("""
        SELECT d.id, d.reference, d.type, d.statut, d.fournisseur, d.description,
               d.montantTtc, d.montantNetAPayer, d.dateCreation,
               COUNT(DISTINCT doc.id),
               COUNT(DISTINCT CASE WHEN rv.statut = 'CONFORME' THEN rv.id END),
               COUNT(DISTINCT rv.id)
        FROM DossierPaiement d
        LEFT JOIN d.documents doc
        LEFT JOIN d.resultatsValidation rv
        GROUP BY d.id, d.reference, d.type, d.statut, d.fournisseur, d.description,
                 d.montantTtc, d.montantNetAPayer, d.dateCreation
    """)
    fun findAllProjected(pageable: Pageable): Page<Array<Any>>
}

interface DocumentRepository : JpaRepository<Document, UUID> {
    fun findByDossierId(dossierId: UUID): List<Document>
    fun findByDossierIdAndTypeDocument(dossierId: UUID, type: TypeDocument): Document?
    fun countByDossierId(dossierId: UUID): Long
}

interface FactureRepository : JpaRepository<Facture, UUID> {
    fun findByDossierId(dossierId: UUID): Facture?
    fun findAllByDossierId(dossierId: UUID): List<Facture>
    fun findByDocumentId(documentId: UUID): Facture?
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
    fun countByDossierId(dossierId: UUID): Long
    fun countByDossierIdAndStatut(dossierId: UUID, statut: StatutCheck): Long
    fun deleteByDossierId(dossierId: UUID)
}

interface AuditLogRepository : JpaRepository<AuditLog, UUID> {
    fun findByDossierIdOrderByDateActionDesc(dossierId: UUID): List<AuditLog>
}
