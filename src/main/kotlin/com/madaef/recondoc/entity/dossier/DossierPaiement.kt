package com.madaef.recondoc.entity.dossier

import com.madaef.recondoc.entity.engagement.Engagement
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(
    name = "dossier_paiement",
    indexes = [
        Index(name = "idx_dossier_statut", columnList = "statut"),
        Index(name = "idx_dossier_reference", columnList = "reference"),
        Index(name = "idx_dossier_type", columnList = "type"),
        Index(name = "idx_dossier_date_creation", columnList = "date_creation"),
        Index(name = "idx_dossier_engagement", columnList = "engagement_id")
    ]
)
class DossierPaiement(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Version
    var version: Long = 0,

    @Column(nullable = false, unique = true)
    var reference: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: DossierType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var statut: StatutDossier = StatutDossier.BROUILLON,

    var fournisseur: String? = null,
    @Column(columnDefinition = "TEXT") var description: String? = null,

    @Column(name = "montant_ttc", precision = 15, scale = 2) var montantTtc: BigDecimal? = null,
    @Column(name = "montant_ht", precision = 15, scale = 2) var montantHt: BigDecimal? = null,
    @Column(name = "montant_tva", precision = 15, scale = 2) var montantTva: BigDecimal? = null,
    @Column(name = "montant_net_a_payer", precision = 15, scale = 2) var montantNetAPayer: BigDecimal? = null,

    @Column(name = "date_creation", nullable = false) var dateCreation: LocalDateTime = LocalDateTime.now(),
    @Column(name = "date_validation") var dateValidation: LocalDateTime? = null,
    @Column(name = "valide_par") var validePar: String? = null,
    @Column(name = "motif_rejet", columnDefinition = "TEXT") var motifRejet: String? = null,

    // CSV de TypeDocument pour R20 ; null = defauts par type de dossier.
    @Column(name = "required_documents", columnDefinition = "TEXT")
    var requiredDocuments: String? = null,

    @OneToMany(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("dateUpload ASC")
    var documents: MutableSet<Document> = mutableSetOf(),

    @OneToMany(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var factures: MutableList<Facture> = mutableListOf(),

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var bonCommande: BonCommande? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var contratAvenant: ContratAvenant? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var ordrePaiement: OrdrePaiement? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var checklistAutocontrole: ChecklistAutocontrole? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var tableauControle: TableauControle? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var pvReception: PvReception? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var attestationFiscale: AttestationFiscale? = null,

    @OneToMany(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("dateExecution ASC")
    var resultatsValidation: MutableSet<ResultatValidation> = mutableSetOf(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engagement_id")
    var engagement: Engagement? = null
)
