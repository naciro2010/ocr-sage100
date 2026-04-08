package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "resultat_validation")
class ResultatValidation(
    @Id @GeneratedValue(strategy = GenerationType.UUID) var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false)
    var dossier: DossierPaiement,

    @Column(nullable = false) var regle: String,
    @Column(columnDefinition = "TEXT", nullable = false) var libelle: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) var statut: StatutCheck,

    @Column(columnDefinition = "TEXT") var detail: String? = null,
    @Column(name = "valeur_attendue", columnDefinition = "TEXT") var valeurAttendue: String? = null,
    @Column(name = "valeur_trouvee", columnDefinition = "TEXT") var valeurTrouvee: String? = null,
    @Column(name = "date_execution", nullable = false) var dateExecution: LocalDateTime = LocalDateTime.now(),
    @Column(nullable = false) var source: String = "DETERMINISTE"
)
