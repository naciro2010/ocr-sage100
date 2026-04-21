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
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID

@Service
class EngagementService(
    private val engagementRepo: EngagementRepository,
    private val dossierRepo: DossierRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        val engagement = engagementRepo.findById(id)
            .orElseThrow { NoSuchElementException("Engagement introuvable: $id") }
        applyUpdatableFields(engagement, request)
        engagement.dateModification = LocalDateTime.now()
        return engagementRepo.save(engagement)
    }

    @Transactional
    fun delete(id: UUID) {
        val engagement = engagementRepo.findById(id)
            .orElseThrow { NoSuchElementException("Engagement introuvable: $id") }
        val nbDossiers = engagementRepo.findDossiersByEngagement(id).size
        if (nbDossiers > 0) {
            throw IllegalStateException("Impossible de supprimer : $nbDossiers dossier(s) rattache(s). Detachez-les d'abord.")
        }
        engagementRepo.delete(engagement)
    }

    @Transactional
    fun attachDossier(engagementId: UUID, dossierId: UUID) {
        val engagement = engagementRepo.findById(engagementId)
            .orElseThrow { NoSuchElementException("Engagement introuvable: $engagementId") }
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

    @Transactional(readOnly = true)
    fun get(id: UUID): EngagementResponse {
        val engagement = engagementRepo.findByIdWithDossiers(id)
            .orElseThrow { NoSuchElementException("Engagement introuvable: $id") }
        return toResponse(engagement)
    }

    @Transactional(readOnly = true)
    fun list(
        statut: StatutEngagement?,
        fournisseur: String?,
        reference: String?,
        pageable: Pageable
    ): Page<EngagementListItem> {
        return engagementRepo.search(statut, fournisseur, reference, pageable)
            .map { toListItem(it) }
    }

    @Transactional(readOnly = true)
    fun tree(id: UUID): EngagementTreeNode {
        val engagement = engagementRepo.findByIdWithDossiers(id)
            .orElseThrow { NoSuchElementException("Engagement introuvable: $id") }
        return EngagementTreeNode(
            id = engagement.id!!,
            type = engagement.typeEngagement(),
            reference = engagement.reference,
            objet = engagement.objet,
            fournisseur = engagement.fournisseur,
            montantTtc = engagement.montantTtc,
            statut = engagement.statut,
            dossiers = engagement.dossiers.map { toDossierAttache(it) }
        )
    }

    @Transactional(readOnly = true)
    fun stats(): EngagementStats {
        val byStatut = engagementRepo.statsByStatut()
            .associate { (it[0] as StatutEngagement) to (it[1] as Long) }
        val nbMarches = engagementRepo.countMarches()
        val nbBc = engagementRepo.countBonsCommande()
        val nbContrats = engagementRepo.countContrats()

        val total = byStatut.values.sum()
        val montantTotal = engagementRepo.findAll().sumOf { it.montantTtc ?: BigDecimal.ZERO }
        val montantConsomme = engagementRepo.findAll()
            .sumOf { engagementRepo.sumDossiersMontantTtc(it.id!!) }

        return EngagementStats(
            totalEngagements = total,
            actifs = byStatut[StatutEngagement.ACTIF] ?: 0L,
            clotures = byStatut[StatutEngagement.CLOTURE] ?: 0L,
            suspendus = byStatut[StatutEngagement.SUSPENDU] ?: 0L,
            nbMarches = nbMarches,
            nbBonsCommande = nbBc,
            nbContrats = nbContrats,
            montantTotalTtc = montantTotal,
            montantTotalConsomme = montantConsomme
        )
    }

    // === Mapping ===

    fun toResponse(engagement: Engagement): EngagementResponse {
        val consomme = engagement.id?.let { engagementRepo.sumDossiersMontantTtc(it) } ?: BigDecimal.ZERO
        val taux = engagement.montantTtc
            ?.takeIf { it > BigDecimal.ZERO }
            ?.let { consomme.divide(it, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP) }

        return EngagementResponse(
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
            marche = (engagement as? EngagementMarche)?.let {
                MarcheDetails(
                    numeroAo = it.numeroAo,
                    dateAo = it.dateAo,
                    categorie = it.categorie,
                    delaiExecutionMois = it.delaiExecutionMois,
                    penalitesRetardJourPct = it.penalitesRetardJourPct,
                    retenueGarantiePct = it.retenueGarantiePct,
                    cautionDefinitivePct = it.cautionDefinitivePct,
                    revisionPrixAutorisee = it.revisionPrixAutorisee
                )
            },
            bonCommande = (engagement as? EngagementBonCommande)?.let {
                BonCommandeDetails(
                    plafondMontant = it.plafondMontant,
                    dateValiditeFin = it.dateValiditeFin,
                    seuilAntiFractionnement = it.seuilAntiFractionnement
                )
            },
            contrat = (engagement as? EngagementContrat)?.let {
                ContratDetails(
                    periodicite = it.periodicite,
                    dateDebut = it.dateDebut,
                    dateFin = it.dateFin,
                    reconductionTacite = it.reconductionTacite,
                    preavisResiliationJours = it.preavisResiliationJours,
                    indiceRevision = it.indiceRevision
                )
            },
            dossiers = engagement.dossiers.map { toDossierAttache(it) },
            montantConsomme = consomme,
            tauxConsommation = taux
        )
    }

    private fun toListItem(engagement: Engagement): EngagementListItem {
        val consomme = engagement.id?.let { engagementRepo.sumDossiersMontantTtc(it) } ?: BigDecimal.ZERO
        val taux = engagement.montantTtc
            ?.takeIf { it > BigDecimal.ZERO }
            ?.let { consomme.divide(it, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP) }
        return EngagementListItem(
            id = engagement.id!!,
            type = engagement.typeEngagement(),
            reference = engagement.reference,
            statut = engagement.statut,
            objet = engagement.objet,
            fournisseur = engagement.fournisseur,
            montantTtc = engagement.montantTtc,
            dateDocument = engagement.dateDocument,
            nbDossiers = engagement.id?.let { engagementRepo.findDossiersByEngagement(it).size.toLong() } ?: 0L,
            montantConsomme = consomme,
            tauxConsommation = taux
        )
    }

    private fun toDossierAttache(dossier: com.madaef.recondoc.entity.dossier.DossierPaiement) = DossierAttache(
        id = dossier.id!!,
        reference = dossier.reference,
        statut = dossier.statut.name,
        fournisseur = dossier.fournisseur,
        montantTtc = dossier.montantTtc,
        dateCreation = dossier.dateCreation
    )

    // === Construction ===

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
            is EngagementMarche -> {
                r.numeroAo?.let { engagement.numeroAo = it }
                r.dateAo?.let { engagement.dateAo = it }
                r.categorie?.let { engagement.categorie = it }
                r.delaiExecutionMois?.let { engagement.delaiExecutionMois = it }
                r.penalitesRetardJourPct?.let { engagement.penalitesRetardJourPct = it }
                r.retenueGarantiePct?.let { engagement.retenueGarantiePct = it }
                r.cautionDefinitivePct?.let { engagement.cautionDefinitivePct = it }
                r.revisionPrixAutorisee?.let { engagement.revisionPrixAutorisee = it }
            }
            is EngagementBonCommande -> {
                r.plafondMontant?.let { engagement.plafondMontant = it }
                r.dateValiditeFin?.let { engagement.dateValiditeFin = it }
                r.seuilAntiFractionnement?.let { engagement.seuilAntiFractionnement = it }
            }
            is EngagementContrat -> {
                r.periodicite?.let { engagement.periodicite = it }
                r.dateDebut?.let { engagement.dateDebut = it }
                r.dateFin?.let { engagement.dateFin = it }
                r.reconductionTacite?.let { engagement.reconductionTacite = it }
                r.preavisResiliationJours?.let { engagement.preavisResiliationJours = it }
                r.indiceRevision?.let { engagement.indiceRevision = it }
            }
        }
    }
}
