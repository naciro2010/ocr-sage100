package com.ocrsage.entity.dossier

import jakarta.persistence.*
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "attestation_fiscale")
class AttestationFiscale(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false, unique = true)
    var dossier: DossierPaiement,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    var numero: String? = null,
    @Column(name = "date_edition") var dateEdition: LocalDate? = null,
    @Column(name = "raison_sociale") var raisonSociale: String? = null,
    @Column(name = "identifiant_fiscal") var identifiantFiscal: String? = null,
    var ice: String? = null,
    var rc: String? = null,
    @Column(name = "est_en_regle") var estEnRegle: Boolean? = null,
    @Column(name = "date_validite") var dateValidite: LocalDate? = null
)
