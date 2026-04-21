package com.madaef.recondoc.entity.engagement

import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.FournisseurCanonique
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class TypeEngagement { MARCHE, BON_COMMANDE, CONTRAT }

enum class StatutEngagement { ACTIF, CLOTURE, SUSPENDU }

enum class CategorieMarche { TRAVAUX, FOURNITURES, SERVICES }

enum class PeriodiciteContrat { MENSUEL, TRIMESTRIEL, SEMESTRIEL, ANNUEL }

@Entity
@Table(
    name = "engagement",
    indexes = [
        Index(name = "idx_engagement_type", columnList = "type"),
        Index(name = "idx_engagement_statut", columnList = "statut"),
        Index(name = "idx_engagement_reference", columnList = "reference"),
        Index(name = "idx_engagement_fournisseur", columnList = "fournisseur"),
        Index(name = "idx_engagement_fournisseur_canonique", columnList = "fournisseur_canonique_id")
    ]
)
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.STRING, length = 30)
abstract class Engagement(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Version
    var version: Long = 0,

    @Column(nullable = false, unique = true)
    var reference: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var statut: StatutEngagement = StatutEngagement.ACTIF,

    @Column(columnDefinition = "TEXT")
    var objet: String? = null,

    var fournisseur: String? = null,

    @Column(name = "montant_ht", precision = 15, scale = 2)
    var montantHt: BigDecimal? = null,

    @Column(name = "montant_tva", precision = 15, scale = 2)
    var montantTva: BigDecimal? = null,

    @Column(name = "taux_tva", precision = 5, scale = 2)
    var tauxTva: BigDecimal? = null,

    @Column(name = "montant_ttc", precision = 15, scale = 2)
    var montantTtc: BigDecimal? = null,

    @Column(name = "date_document")
    var dateDocument: LocalDate? = null,

    @Column(name = "date_signature")
    var dateSignature: LocalDate? = null,

    @Column(name = "date_notification")
    var dateNotification: LocalDate? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_canonique_id")
    var fournisseurCanonique: FournisseurCanonique? = null,

    @Column(name = "date_creation", nullable = false)
    var dateCreation: LocalDateTime = LocalDateTime.now(),

    @Column(name = "date_modification")
    var dateModification: LocalDateTime? = null,

    @OneToMany(mappedBy = "engagement", fetch = FetchType.LAZY)
    @OrderBy("dateCreation ASC")
    var dossiers: MutableList<DossierPaiement> = mutableListOf()
) {
    abstract fun typeEngagement(): TypeEngagement
}

@Entity
@Table(name = "engagement_marche")
@DiscriminatorValue("MARCHE")
class EngagementMarche(
    @Column(name = "numero_ao")
    var numeroAo: String? = null,

    @Column(name = "date_ao")
    var dateAo: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "categorie")
    var categorie: CategorieMarche? = null,

    @Column(name = "delai_execution_mois")
    var delaiExecutionMois: Int? = null,

    @Column(name = "penalites_retard_jour_pct", precision = 6, scale = 4)
    var penalitesRetardJourPct: BigDecimal? = null,

    @Column(name = "retenue_garantie_pct", precision = 5, scale = 2)
    var retenueGarantiePct: BigDecimal? = null,

    @Column(name = "caution_definitive_pct", precision = 5, scale = 2)
    var cautionDefinitivePct: BigDecimal? = null,

    @Column(name = "revision_prix_autorisee", nullable = false)
    var revisionPrixAutorisee: Boolean = false
) : Engagement() {
    override fun typeEngagement() = TypeEngagement.MARCHE
}

@Entity
@Table(name = "engagement_bon_commande")
@DiscriminatorValue("BON_COMMANDE")
class EngagementBonCommande(
    @Column(name = "plafond_montant", precision = 15, scale = 2)
    var plafondMontant: BigDecimal? = null,

    @Column(name = "date_validite_fin")
    var dateValiditeFin: LocalDate? = null,

    @Column(name = "seuil_anti_fractionnement", precision = 15, scale = 2)
    var seuilAntiFractionnement: BigDecimal? = null
) : Engagement() {
    override fun typeEngagement() = TypeEngagement.BON_COMMANDE
}

@Entity
@Table(name = "engagement_contrat")
@DiscriminatorValue("CONTRAT")
class EngagementContrat(
    @Enumerated(EnumType.STRING)
    @Column(name = "periodicite")
    var periodicite: PeriodiciteContrat? = null,

    @Column(name = "date_debut")
    var dateDebut: LocalDate? = null,

    @Column(name = "date_fin")
    var dateFin: LocalDate? = null,

    @Column(name = "reconduction_tacite", nullable = false)
    var reconductionTacite: Boolean = false,

    @Column(name = "preavis_resiliation_jours")
    var preavisResiliationJours: Int? = null,

    @Column(name = "indice_revision")
    var indiceRevision: String? = null
) : Engagement() {
    override fun typeEngagement() = TypeEngagement.CONTRAT
}
