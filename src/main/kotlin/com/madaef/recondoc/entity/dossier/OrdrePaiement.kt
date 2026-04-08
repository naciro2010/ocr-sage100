package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "ordre_paiement")
class OrdrePaiement(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false, unique = true)
    var dossier: DossierPaiement,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @Column(name = "numero_op") var numeroOp: String? = null,
    @Column(name = "date_emission") var dateEmission: LocalDate? = null,
    var emetteur: String? = null,
    @Column(name = "nature_operation") var natureOperation: String? = null,
    @Column(columnDefinition = "TEXT") var description: String? = null,
    var beneficiaire: String? = null,
    var rib: String? = null,
    var banque: String? = null,

    @Column(name = "montant_operation", precision = 15, scale = 2) var montantOperation: BigDecimal? = null,
    @Column(name = "reference_facture") var referenceFacture: String? = null,
    @Column(name = "reference_bc_ou_contrat") var referenceBcOuContrat: String? = null,
    @Column(name = "reference_sage") var referenceSage: String? = null,
    @Column(name = "conclusion_controleur", columnDefinition = "TEXT") var conclusionControleur: String? = null,
    @Column(name = "pieces_justificatives", columnDefinition = "TEXT") var piecesJustificatives: String? = null, // JSON array as string

    @OneToMany(mappedBy = "ordrePaiement", cascade = [CascadeType.ALL], orphanRemoval = true)
    var retenues: MutableList<Retenue> = mutableListOf()
)

@Entity
@Table(name = "retenue")
class Retenue(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "op_id", nullable = false)
    var ordrePaiement: OrdrePaiement,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) var type: TypeRetenue,

    @Column(name = "article_cgi") var articleCgi: String? = null,
    @Column(precision = 15, scale = 2) var base: BigDecimal? = null,
    @Column(precision = 5, scale = 2) var taux: BigDecimal? = null,
    @Column(precision = 15, scale = 2) var montant: BigDecimal? = null
)
