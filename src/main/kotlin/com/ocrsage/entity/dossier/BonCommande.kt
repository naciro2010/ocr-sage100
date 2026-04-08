package com.ocrsage.entity.dossier

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "bon_commande")
class BonCommande(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false, unique = true)
    var dossier: DossierPaiement,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    var reference: String? = null,
    @Column(name = "date_bc") var dateBc: LocalDate? = null,
    var fournisseur: String? = null,
    @Column(columnDefinition = "TEXT") var objet: String? = null,

    @Column(name = "montant_ht", precision = 15, scale = 2) var montantHt: BigDecimal? = null,
    @Column(name = "montant_tva", precision = 15, scale = 2) var montantTva: BigDecimal? = null,
    @Column(name = "taux_tva", precision = 5, scale = 2) var tauxTva: BigDecimal? = null,
    @Column(name = "montant_ttc", precision = 15, scale = 2) var montantTtc: BigDecimal? = null,

    var signataire: String? = null
)
