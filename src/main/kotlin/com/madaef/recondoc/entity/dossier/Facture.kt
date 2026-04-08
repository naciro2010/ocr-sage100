package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "facture")
class Facture(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false, unique = true)
    var dossier: DossierPaiement,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @Column(name = "numero_facture") var numeroFacture: String? = null,
    @Column(name = "date_facture") var dateFacture: LocalDate? = null,
    var fournisseur: String? = null,
    var client: String? = null,
    var ice: String? = null,
    @Column(name = "identifiant_fiscal") var identifiantFiscal: String? = null,
    var rc: String? = null,
    var rib: String? = null,

    @Column(name = "montant_ht", precision = 15, scale = 2) var montantHt: BigDecimal? = null,
    @Column(name = "montant_tva", precision = 15, scale = 2) var montantTva: BigDecimal? = null,
    @Column(name = "taux_tva", precision = 5, scale = 2) var tauxTva: BigDecimal? = null,
    @Column(name = "montant_ttc", precision = 15, scale = 2) var montantTtc: BigDecimal? = null,

    @Column(name = "reference_contrat") var referenceContrat: String? = null,
    var periode: String? = null,

    @OneToMany(mappedBy = "facture", cascade = [CascadeType.ALL], orphanRemoval = true)
    var lignes: MutableList<LigneFacture> = mutableListOf()
)

@Entity
@Table(name = "ligne_facture")
class LigneFacture(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facture_id", nullable = false)
    var facture: Facture,

    @Column(name = "code_article") var codeArticle: String? = null,
    @Column(columnDefinition = "TEXT", nullable = false) var designation: String,
    @Column(precision = 15, scale = 3) var quantite: BigDecimal? = null,
    var unite: String? = null,
    @Column(name = "prix_unitaire_ht", precision = 15, scale = 2) var prixUnitaireHt: BigDecimal? = null,
    @Column(name = "montant_total_ht", precision = 15, scale = 2) var montantTotalHt: BigDecimal? = null
)
