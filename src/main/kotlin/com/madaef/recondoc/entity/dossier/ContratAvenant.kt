package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "contrat_avenant")
class ContratAvenant(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false, unique = true)
    var dossier: DossierPaiement,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @Column(name = "reference_contrat") var referenceContrat: String? = null,
    @Column(name = "numero_avenant") var numeroAvenant: String? = null,
    @Column(name = "date_signature") var dateSignature: LocalDate? = null,
    @Column(columnDefinition = "TEXT") var parties: String? = null, // JSON array as string
    @Column(columnDefinition = "TEXT") var objet: String? = null,
    @Column(name = "date_effet") var dateEffet: LocalDate? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_canonique_id")
    var fournisseurCanonique: FournisseurCanonique? = null,

    @OneToMany(mappedBy = "contratAvenant", cascade = [CascadeType.ALL], orphanRemoval = true)
    var grillesTarifaires: MutableList<GrilleTarifaire> = mutableListOf()
)

@Entity
@Table(name = "grille_tarifaire")
class GrilleTarifaire(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contrat_avenant_id", nullable = false)
    var contratAvenant: ContratAvenant,

    @Column(columnDefinition = "TEXT", nullable = false) var designation: String,
    @Column(name = "prix_unitaire_ht", precision = 15, scale = 2) var prixUnitaireHt: BigDecimal? = null,
    @Enumerated(EnumType.STRING) var periodicite: Periodicite? = null,
    var entite: String? = null
)
