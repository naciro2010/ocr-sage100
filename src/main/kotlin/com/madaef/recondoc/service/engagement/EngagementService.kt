package com.madaef.recondoc.service.engagement

import com.madaef.recondoc.dto.engagement.*
import com.madaef.recondoc.entity.engagement.*
import com.madaef.recondoc.repository.dossier.DossierRepository
import com.madaef.recondoc.repository.engagement.EngagementRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Service
class EngagementService(
    private val engagementRepo: EngagementRepository,
    private val dossierRepo: DossierRepository,
    private val mapper: EngagementMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // === Commandes ===

    @Transactional
    fun create(request: CreateEngagementRequest): Engagement {
        if (engagementRepo.existsByReference(request.reference)) {
            throw IllegalArgumentException("Un engagement avec la reference '${request.reference}' existe deja")
        }
        val engagement: Engagement = when (request.type) {
            TypeEngagement.MARCHE -> buildMarche(request)
            TypeEngagement.BON_COMMANDE -> buildBonCommande(request)
            TypeEngagement.CONTRAT -> buildContrat(request)
        }
        applyCommonFields(engagement, request)
        val saved = engagementRepo.save(engagement)
        log.info("Engagement cree: type={} ref={} id={}", saved.typeEngagement(), saved.reference, saved.id)
        return saved
    }

    @Transactional
    fun update(id: UUID, request: UpdateEngagementRequest): Engagement {
        val engagement = findOrThrow(id)
        applyUpdatableFields(engagement, request)
        engagement.dateModification = LocalDateTime.now()
        return engagementRepo.save(engagement)
    }

    @Transactional
    fun delete(id: UUID) {
        val engagement = findOrThrow(id)
        val nbDossiers = engagementRepo.findDossiersByEngagement(id).size
        if (nbDossiers > 0) {
            throw IllegalStateException("Impossible de supprimer : $nbDossiers dossier(s) rattache(s). Detachez-les d'abord.")
        }
        engagementRepo.delete(engagement)
    }

    @Transactional
    fun attachDossier(engagementId: UUID, dossierId: UUID) {
        val engagement = findOrThrow(engagementId)
        if (engagement.statut == StatutEngagement.CLOTURE) {
            throw IllegalStateException("L'engagement est CLOTURE, rattachement impossible")
        }
        val dossier = dossierRepo.findById(dossierId)
            .orElseThrow { NoSuchElementException("Dossier introuvable: $dossierId") }
        dossier.engagement = engagement
        dossierRepo.save(dossier)
    }

    @Transactional
    fun detachDossier(dossierId: UUID) {
        val dossier = dossierRepo.findById(dossierId)
            .orElseThrow { NoSuchElementException("Dossier introuvable: $dossierId") }
        dossier.engagement = null
        dossierRepo.save(dossier)
    }

    // === Queries ===

    @Transactional(readOnly = true)
    fun get(id: UUID): EngagementResponse {
        val engagement = engagementRepo.findByIdWithDossiers(id)
            .orElseThrow { NoSuchElementException("Engagement introuvable: $id") }
        val montantConsomme = engagementRepo.sumDossiersMontantTtc(id)
        return mapper.toResponse(engagement, montantConsomme, engagement.dossiers.toList())
    }

    @Transactional(readOnly = true)
    fun list(
        statut: StatutEngagement?,
        fournisseur: String?,
        reference: String?,
        pageable: Pageable
    ): Page<EngagementListItem> {
        val page = engagementRepo.search(statut, fournisseur, reference, pageable)
        val ids = page.content.mapNotNull { it.id }
        val aggregates = if (ids.isEmpty()) emptyMap() else {
            engagementRepo.aggregateDossiersByEngagements(ids)
                .associate { (it[0] as UUID) to Pair(it[1] as Long, it[2] as BigDecimal) }
        }
        return page.map { e ->
            val (nb, somme) = aggregates[e.id] ?: (0L to BigDecimal.ZERO)
            mapper.toListItem(e, somme, nb)
        }
    }

    @Transactional(readOnly = true)
    fun tree(id: UUID): EngagementTreeNode {
        val engagement = engagementRepo.findByIdWithDossiers(id)
            .orElseThrow { NoSuchElementException("Engagement introuvable: $id") }
        return mapper.toTreeNode(engagement, engagement.dossiers.toList())
    }

    @Transactional(readOnly = true)
    fun stats(): EngagementStats {
        val byStatut = engagementRepo.statsByStatut()
            .associate { (it[0] as StatutEngagement) to (it[1] as Long) }
        return EngagementStats(
            totalEngagements = byStatut.values.sum(),
            actifs = byStatut[StatutEngagement.ACTIF] ?: 0L,
            clotures = byStatut[StatutEngagement.CLOTURE] ?: 0L,
            suspendus = byStatut[StatutEngagement.SUSPENDU] ?: 0L,
            nbMarches = engagementRepo.countMarches(),
            nbBonsCommande = engagementRepo.countBonsCommande(),
            nbContrats = engagementRepo.countContrats(),
            montantTotalTtc = engagementRepo.sumMontantTtcTousEngagements(),
            montantTotalConsomme = engagementRepo.sumMontantConsommeTousEngagements()
        )
    }

    // === Helpers prives ===

    private fun findOrThrow(id: UUID): Engagement = engagementRepo.findById(id)
        .orElseThrow { NoSuchElementException("Engagement introuvable: $id") }

    private fun buildMarche(r: CreateEngagementRequest) = EngagementMarche(
        numeroAo = r.numeroAo,
        dateAo = r.dateAo,
        categorie = r.categorie,
        delaiExecutionMois = r.delaiExecutionMois,
        penalitesRetardJourPct = r.penalitesRetardJourPct,
        retenueGarantiePct = r.retenueGarantiePct,
        cautionDefinitivePct = r.cautionDefinitivePct,
        revisionPrixAutorisee = r.revisionPrixAutorisee ?: false
    )

    private fun buildBonCommande(r: CreateEngagementRequest) = EngagementBonCommande(
        plafondMontant = r.plafondMontant,
        dateValiditeFin = r.dateValiditeFin,
        seuilAntiFractionnement = r.seuilAntiFractionnement
    )

    private fun buildContrat(r: CreateEngagementRequest) = EngagementContrat(
        periodicite = r.periodicite,
        dateDebut = r.dateDebut,
        dateFin = r.dateFin,
        reconductionTacite = r.reconductionTacite ?: false,
        preavisResiliationJours = r.preavisResiliationJours,
        indiceRevision = r.indiceRevision
    )

    private fun applyCommonFields(engagement: Engagement, r: CreateEngagementRequest) {
        engagement.reference = r.reference
        engagement.objet = r.objet
        engagement.fournisseur = r.fournisseur
        engagement.montantHt = r.montantHt
        engagement.montantTva = r.montantTva
        engagement.tauxTva = r.tauxTva
        engagement.montantTtc = r.montantTtc
        engagement.dateDocument = r.dateDocument
        engagement.dateSignature = r.dateSignature
        engagement.dateNotification = r.dateNotification
        r.statut?.let { engagement.statut = it }
    }

    private fun applyUpdatableFields(engagement: Engagement, r: UpdateEngagementRequest) {
        r.objet?.let { engagement.objet = it }
        r.fournisseur?.let { engagement.fournisseur = it }
        r.montantHt?.let { engagement.montantHt = it }
        r.montantTva?.let { engagement.montantTva = it }
        r.tauxTva?.let { engagement.tauxTva = it }
        r.montantTtc?.let { engagement.montantTtc = it }
        r.dateDocument?.let { engagement.dateDocument = it }
        r.dateSignature?.let { engagement.dateSignature = it }
        r.dateNotification?.let { engagement.dateNotification = it }
        r.statut?.let { engagement.statut = it }

        when (engagement) {
            is EngagementMarche -> applyMarche(engagement, r)
            is EngagementBonCommande -> applyBc(engagement, r)
            is EngagementContrat -> applyContrat(engagement, r)
        }
    }

    private fun applyMarche(e: EngagementMarche, r: UpdateEngagementRequest) {
        r.numeroAo?.let { e.numeroAo = it }
        r.dateAo?.let { e.dateAo = it }
        r.categorie?.let { e.categorie = it }
        r.delaiExecutionMois?.let { e.delaiExecutionMois = it }
        r.penalitesRetardJourPct?.let { e.penalitesRetardJourPct = it }
        r.retenueGarantiePct?.let { e.retenueGarantiePct = it }
        r.cautionDefinitivePct?.let { e.cautionDefinitivePct = it }
        r.revisionPrixAutorisee?.let { e.revisionPrixAutorisee = it }
    }

    private fun applyBc(e: EngagementBonCommande, r: UpdateEngagementRequest) {
        r.plafondMontant?.let { e.plafondMontant = it }
        r.dateValiditeFin?.let { e.dateValiditeFin = it }
        r.seuilAntiFractionnement?.let { e.seuilAntiFractionnement = it }
    }

    private fun applyContrat(e: EngagementContrat, r: UpdateEngagementRequest) {
        r.periodicite?.let { e.periodicite = it }
        r.dateDebut?.let { e.dateDebut = it }
        r.dateFin?.let { e.dateFin = it }
        r.reconductionTacite?.let { e.reconductionTacite = it }
        r.preavisResiliationJours?.let { e.preavisResiliationJours = it }
        r.indiceRevision?.let { e.indiceRevision = it }
    }
}
