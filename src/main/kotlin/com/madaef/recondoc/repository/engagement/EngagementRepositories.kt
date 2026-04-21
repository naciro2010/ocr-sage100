package com.madaef.recondoc.repository.engagement

import com.madaef.recondoc.entity.engagement.Engagement
import com.madaef.recondoc.entity.engagement.EngagementBonCommande
import com.madaef.recondoc.entity.engagement.EngagementContrat
import com.madaef.recondoc.entity.engagement.EngagementMarche
import com.madaef.recondoc.entity.engagement.StatutEngagement
import com.madaef.recondoc.entity.engagement.TypeEngagement
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface EngagementRepository : JpaRepository<Engagement, UUID> {

    fun findByReference(reference: String): Engagement?

    fun existsByReference(reference: String): Boolean

    @EntityGraph(attributePaths = ["dossiers", "fournisseurCanonique"])
    @Query("SELECT e FROM Engagement e WHERE e.id = :id")
    fun findByIdWithDossiers(id: UUID): Optional<Engagement>

    @Query("""
        SELECT e FROM Engagement e
        WHERE (:statut IS NULL OR e.statut = :statut)
        AND (:fournisseur IS NULL OR LOWER(CAST(e.fournisseur AS string)) LIKE LOWER(CONCAT('%', CAST(:fournisseur AS string), '%')))
        AND (:reference IS NULL OR LOWER(CAST(e.reference AS string)) LIKE LOWER(CONCAT('%', CAST(:reference AS string), '%')))
    """)
    fun search(statut: StatutEngagement?, fournisseur: String?, reference: String?, pageable: Pageable): Page<Engagement>

    @Query("""
        SELECT e.statut AS statut, COUNT(e) AS cnt, COALESCE(SUM(e.montantTtc), 0) AS total
        FROM Engagement e GROUP BY e.statut
    """)
    fun statsByStatut(): List<Array<Any>>

    @Query("SELECT COUNT(e) FROM EngagementMarche e")
    fun countMarches(): Long

    @Query("SELECT COUNT(e) FROM EngagementBonCommande e")
    fun countBonsCommande(): Long

    @Query("SELECT COUNT(e) FROM EngagementContrat e")
    fun countContrats(): Long

    /**
     * Somme des montants TTC des dossiers rattaches a un engagement.
     * Utilise par R-E01 (verification plafond).
     */
    @Query("""
        SELECT COALESCE(SUM(d.montantTtc), 0)
        FROM DossierPaiement d
        WHERE d.engagement.id = :engagementId
    """)
    fun sumDossiersMontantTtc(engagementId: UUID): java.math.BigDecimal

    /**
     * Dossiers rattaches tries par date de creation. Utilise par R-M06
     * (chronologie des decomptes) et R-C03 (echeance periodique).
     */
    @Query("""
        SELECT d FROM DossierPaiement d
        WHERE d.engagement.id = :engagementId
        ORDER BY d.dateCreation ASC
    """)
    fun findDossiersByEngagement(engagementId: UUID): List<com.madaef.recondoc.entity.dossier.DossierPaiement>
}

interface EngagementMarcheRepository : JpaRepository<EngagementMarche, UUID> {
    fun findByNumeroAo(numeroAo: String): List<EngagementMarche>
}

interface EngagementBonCommandeRepository : JpaRepository<EngagementBonCommande, UUID> {
    /**
     * Somme des TTC des BC du meme fournisseur sur les 12 derniers mois.
     * Support de R-B02 (anti-fractionnement).
     */
    @Query("""
        SELECT COALESCE(SUM(e.montantTtc), 0)
        FROM EngagementBonCommande e
        WHERE LOWER(TRIM(e.fournisseur)) = LOWER(TRIM(:fournisseur))
        AND e.dateDocument >= :dateFrom
        AND (:excludeId IS NULL OR e.id <> :excludeId)
    """)
    fun sumMontantByFournisseurSince(
        fournisseur: String,
        dateFrom: java.time.LocalDate,
        excludeId: UUID?
    ): java.math.BigDecimal
}

interface EngagementContratRepository : JpaRepository<EngagementContrat, UUID>
