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
        AND (:fournisseur IS NULL OR LOWER(CAST(d.fournisseur AS string)) LIKE LOWER(CONCAT('%', CAST(:fournisseur AS string), '%')))
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

    @Query("""
        SELECT d.fournisseur AS nom,
               COUNT(d) AS nbDossiers,
               COUNT(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.BROUILLON THEN 1 END) AS nbBrouillons,
               COUNT(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.EN_VERIFICATION THEN 1 END) AS nbEnVerif,
               COUNT(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.VALIDE THEN 1 END) AS nbValides,
               COUNT(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.REJETE THEN 1 END) AS nbRejetes,
               COALESCE(SUM(d.montantTtc), 0) AS montantTotal,
               COALESCE(SUM(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.VALIDE THEN d.montantTtc ELSE 0 END), 0) AS montantValide,
               MAX(d.dateCreation) AS dernier,
               MIN(d.dateCreation) AS premier
        FROM DossierPaiement d
        WHERE d.fournisseur IS NOT NULL AND TRIM(d.fournisseur) <> ''
        AND (:q IS NULL OR LOWER(CAST(d.fournisseur AS string)) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
        GROUP BY d.fournisseur
        ORDER BY COUNT(d) DESC
    """)
    fun aggregateByFournisseur(q: String?): List<Array<Any?>>

    @Query("""
        SELECT d.fournisseur AS nom,
               COUNT(d) AS nbDossiers,
               COUNT(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.BROUILLON THEN 1 END) AS nbBrouillons,
               COUNT(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.EN_VERIFICATION THEN 1 END) AS nbEnVerif,
               COUNT(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.VALIDE THEN 1 END) AS nbValides,
               COUNT(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.REJETE THEN 1 END) AS nbRejetes,
               COALESCE(SUM(d.montantTtc), 0) AS montantTotal,
               COALESCE(SUM(d.montantHt), 0) AS montantHt,
               COALESCE(SUM(d.montantTva), 0) AS montantTva,
               COALESCE(SUM(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.VALIDE THEN d.montantTtc ELSE 0 END), 0) AS montantValide,
               COALESCE(SUM(CASE WHEN d.statut = com.madaef.recondoc.entity.dossier.StatutDossier.EN_VERIFICATION THEN d.montantTtc ELSE 0 END), 0) AS montantEnCours,
               MAX(d.dateCreation) AS dernier,
               MIN(d.dateCreation) AS premier
        FROM DossierPaiement d
        WHERE LOWER(CAST(d.fournisseur AS string)) = LOWER(CAST(:nom AS string))
        GROUP BY d.fournisseur
    """)
    fun aggregateOneFournisseur(nom: String): List<Array<Any?>>

    @Query("""
        SELECT f.fournisseur, f.ice, f.identifiantFiscal, f.rc, f.rib
        FROM Facture f
        WHERE LOWER(CAST(f.fournisseur AS string)) = LOWER(CAST(:nom AS string))
        ORDER BY f.id DESC
    """)
    fun findFactureIdentitiesByFournisseur(nom: String, pageable: Pageable): List<Array<Any?>>

    @Query("""
        SELECT DISTINCT f.fournisseur, f.ice, f.identifiantFiscal, f.rib
        FROM Facture f
        WHERE f.fournisseur IS NOT NULL AND TRIM(f.fournisseur) <> ''
    """)
    fun findAllFactureIdentities(): List<Array<Any?>>

    @EntityGraph(attributePaths = ["documents", "resultatsValidation"])
    fun findByFournisseurIgnoreCaseOrderByDateCreationDesc(fournisseur: String): List<DossierPaiement>
}

interface DocumentRepository : JpaRepository<Document, UUID> {
    fun findByDossierId(dossierId: UUID): List<Document>
    fun findByDossierIdAndTypeDocument(dossierId: UUID, type: TypeDocument): Document?
    fun countByDossierId(dossierId: UUID): Long
    fun findFirstByDossierIdAndFileHash(dossierId: UUID, fileHash: String): Document?
}

interface FactureRepository : JpaRepository<Facture, UUID> {
    fun findByDossierId(dossierId: UUID): Facture?
    fun findAllByDossierId(dossierId: UUID): List<Facture>
    fun findByDocumentId(documentId: UUID): Facture?

    @Query("""
        SELECT f FROM Facture f
        WHERE f.dossier.id <> :excludeDossierId
        AND f.numeroFacture IS NOT NULL
        AND LOWER(TRIM(f.numeroFacture)) = LOWER(TRIM(:numeroFacture))
        AND (f.dateFacture IS NULL OR f.dateFacture >= :dateFrom)
    """)
    fun findByNumeroFacture(
        numeroFacture: String,
        dateFrom: java.time.LocalDate,
        excludeDossierId: UUID
    ): List<Facture>

    @Query("""
        SELECT f FROM Facture f
        WHERE f.dossier.id <> :excludeDossierId
        AND f.fournisseur IS NOT NULL
        AND LOWER(TRIM(f.fournisseur)) = LOWER(TRIM(:fournisseur))
        AND f.montantTtc IS NOT NULL
        AND f.montantTtc BETWEEN :montantMin AND :montantMax
        AND f.dateFacture BETWEEN :dateMin AND :dateMax
    """)
    fun findByMontantFournisseurDate(
        fournisseur: String,
        montantMin: java.math.BigDecimal,
        montantMax: java.math.BigDecimal,
        dateMin: java.time.LocalDate,
        dateMax: java.time.LocalDate,
        excludeDossierId: UUID
    ): List<Facture>
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
    fun deleteByRegle(regle: String)

    @Query("""
        SELECT r.regle,
               COUNT(r),
               AVG(r.durationMs),
               MAX(r.durationMs),
               MIN(r.durationMs)
        FROM ResultatValidation r
        WHERE r.durationMs IS NOT NULL
        GROUP BY r.regle
        ORDER BY AVG(r.durationMs) DESC
    """)
    fun aggregateDurationByRule(): List<Array<Any?>>
}

interface AuditLogRepository : JpaRepository<AuditLog, UUID> {
    fun findByDossierIdOrderByDateActionDesc(dossierId: UUID): List<AuditLog>
}

interface RuleConfigRepository : JpaRepository<RuleConfig, UUID> {
    fun findByRegle(regle: String): RuleConfig?
}

interface DossierRuleOverrideRepository : JpaRepository<DossierRuleOverride, UUID> {
    fun findByDossierId(dossierId: UUID): List<DossierRuleOverride>
    fun findByDossierIdAndRegle(dossierId: UUID, regle: String): DossierRuleOverride?
    fun deleteByDossierIdAndRegle(dossierId: UUID, regle: String)
    fun deleteByRegle(regle: String)
}

interface CustomValidationRuleRepository : JpaRepository<CustomValidationRule, UUID> {
    fun findByCode(code: String): CustomValidationRule?
    fun findAllByOrderByCodeAsc(): List<CustomValidationRule>
    fun findByEnabledTrueOrderByCodeAsc(): List<CustomValidationRule>
}
