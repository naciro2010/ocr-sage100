package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

/**
 * Override humain d'un champ extrait. Stocke (document, champ, valeur corrigee).
 * Applique a la volee par DocumentCorrectionApplier avant chaque evaluation de
 * regle : c'est la source unique de verite des corrections, ce qui evite que
 * les verdicts post-correction divergent de l'etat affiche cote UI.
 *
 * Voir migration V38.
 */
@Entity
@Table(
    name = "document_correction",
    uniqueConstraints = [UniqueConstraint(name = "uq_document_correction_doc_champ", columnNames = ["document_id", "champ"])]
)
class DocumentCorrection(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    @Column(nullable = false) var champ: String,

    @Column(name = "valeur_originale", columnDefinition = "TEXT") var valeurOriginale: String? = null,
    @Column(name = "valeur_corrigee", columnDefinition = "TEXT") var valeurCorrigee: String? = null,

    @Column(length = 64) var regle: String? = null,
    @Column(columnDefinition = "TEXT") var motif: String? = null,

    @Column(name = "corrige_par") var corrigePar: String? = null,
    @Column(name = "date_correction", nullable = false) var dateCorrection: LocalDateTime = LocalDateTime.now()
)
