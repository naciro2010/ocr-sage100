package com.ocrsage.entity.dossier

import jakarta.persistence.*
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "checklist_autocontrole")
class ChecklistAutocontrole(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false, unique = true)
    var dossier: DossierPaiement,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    var document: Document,

    var reference: String? = null,
    @Column(name = "nom_projet") var nomProjet: String? = null,
    @Column(name = "reference_facture") var referenceFacture: String? = null,
    var prestataire: String? = null,

    @OneToMany(mappedBy = "checklist", cascade = [CascadeType.ALL], orphanRemoval = true)
    var points: MutableList<PointControle> = mutableListOf(),

    @OneToMany(mappedBy = "checklist", cascade = [CascadeType.ALL], orphanRemoval = true)
    var signataires: MutableList<SignataireChecklist> = mutableListOf()
)

@Entity
@Table(name = "point_controle")
class PointControle(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checklist_id", nullable = false)
    var checklist: ChecklistAutocontrole,

    var numero: Int,
    @Column(columnDefinition = "TEXT") var description: String? = null,
    @Column(name = "est_valide") var estValide: Boolean? = null,
    @Column(columnDefinition = "TEXT") var observation: String? = null
)

@Entity
@Table(name = "signataire_checklist")
class SignataireChecklist(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checklist_id", nullable = false)
    var checklist: ChecklistAutocontrole,

    var nom: String? = null,
    @Column(name = "date_signature") var dateSignature: LocalDate? = null,
    @Column(name = "a_signature") var aSignature: Boolean = false
)
