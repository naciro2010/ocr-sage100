package com.ocrsage.entity.dossier

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "dossier_paiement")
class DossierPaiement(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

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

    @OneToMany(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var documents: MutableList<Document> = mutableListOf(),

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var facture: Facture? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var bonCommande: BonCommande? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var contratAvenant: ContratAvenant? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var ordrePaiement: OrdrePaiement? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var checklistAutocontrole: ChecklistAutocontrole? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var tableauControle: TableauControle? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var pvReception: PvReception? = null,

    @OneToOne(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var attestationFiscale: AttestationFiscale? = null,

    @OneToMany(mappedBy = "dossier", cascade = [CascadeType.ALL], orphanRemoval = true)
    var resultatsValidation: MutableList<ResultatValidation> = mutableListOf()
)
