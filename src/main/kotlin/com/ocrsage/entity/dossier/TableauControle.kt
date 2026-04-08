package com.ocrsage.entity.dossier

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "tableau_controle")
class TableauControle(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false, unique = true)
    var dossier: DossierPaiement,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @Column(name = "societe_geree") var societeGeree: String? = null,
    @Column(name = "reference_facture") var referenceFacture: String? = null,
    var fournisseur: String? = null,
    var signataire: String? = null,

    @OneToMany(mappedBy = "tableauControle", cascade = [CascadeType.ALL], orphanRemoval = true)
    var points: MutableList<PointControleFinancier> = mutableListOf()
)

@Entity
@Table(name = "point_controle_financier")
class PointControleFinancier(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tableau_controle_id", nullable = false)
    var tableauControle: TableauControle,

    var numero: Int,
    @Column(columnDefinition = "TEXT") var description: String? = null,
    var observation: String? = null,
    @Column(columnDefinition = "TEXT") var commentaire: String? = null
)
