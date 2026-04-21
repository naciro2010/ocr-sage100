package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "fournisseur_canonique")
class FournisseurCanonique(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @Column(name = "nom_canonique", nullable = false, columnDefinition = "TEXT")
    var nomCanonique: String,

    @Column(name = "nom_normalise", nullable = false, columnDefinition = "TEXT")
    var nomNormalise: String,

    @Column(name = "source_type_document", nullable = false)
    var sourceTypeDocument: String,

    @Column(name = "ice") var ice: String? = null,
    @Column(name = "identifiant_fiscal") var identifiantFiscal: String? = null,
    @Column(name = "rib") var rib: String? = null,

    @Column(name = "date_creation", nullable = false)
    var dateCreation: LocalDateTime = LocalDateTime.now(),

    @Column(name = "date_mise_a_jour", nullable = false)
    var dateMiseAJour: LocalDateTime = LocalDateTime.now(),

    @Column(name = "manuellement_confirme", nullable = false)
    var manuellementConfirme: Boolean = false
)

@Entity
@Table(name = "fournisseur_alias")
class FournisseurAlias(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "canonique_id", nullable = false)
    var canonique: FournisseurCanonique,

    @Column(name = "nom_brut", nullable = false, columnDefinition = "TEXT")
    var nomBrut: String,

    @Column(name = "nom_normalise", nullable = false, columnDefinition = "TEXT")
    var nomNormalise: String,

    @Column(name = "source_type_document", nullable = false)
    var sourceTypeDocument: String,

    @Column(name = "similarity_score", precision = 4, scale = 3)
    var similarityScore: java.math.BigDecimal? = null,

    @Column(name = "requires_review", nullable = false)
    var requiresReview: Boolean = false,

    @Column(name = "date_creation", nullable = false)
    var dateCreation: LocalDateTime = LocalDateTime.now()
)
