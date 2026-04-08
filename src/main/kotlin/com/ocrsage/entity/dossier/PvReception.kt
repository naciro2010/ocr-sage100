package com.ocrsage.entity.dossier

import jakarta.persistence.*
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "pv_reception")
class PvReception(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false, unique = true)
    var dossier: DossierPaiement,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    var titre: String? = null,
    @Column(name = "date_reception") var dateReception: LocalDate? = null,
    @Column(name = "reference_contrat") var referenceContrat: String? = null,
    @Column(name = "periode_debut") var periodeDebut: LocalDate? = null,
    @Column(name = "periode_fin") var periodeFin: LocalDate? = null,
    @Column(columnDefinition = "TEXT[]") var prestations: Array<String>? = null,
    @Column(name = "signataire_madaef") var signataireMadaef: String? = null,
    @Column(name = "signataire_fournisseur") var signataireFournisseur: String? = null
)
