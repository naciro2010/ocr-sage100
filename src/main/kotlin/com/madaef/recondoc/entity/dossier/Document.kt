package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "document")
class Document(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dossier_id", nullable = false)
    var dossier: DossierPaiement,

    @Enumerated(EnumType.STRING)
    @Column(name = "type_document", nullable = false)
    var typeDocument: TypeDocument,

    @Column(name = "nom_fichier", nullable = false) var nomFichier: String,
    @Column(name = "chemin_fichier", nullable = false) var cheminFichier: String,

    @Column(name = "texte_extrait", columnDefinition = "TEXT") var texteExtrait: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "donnees_extraites", columnDefinition = "jsonb")
    var donneesExtraites: Map<String, Any?>? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "statut_extraction", nullable = false)
    var statutExtraction: StatutExtraction = StatutExtraction.EN_ATTENTE,

    @Column(name = "erreur_extraction", columnDefinition = "TEXT") var erreurExtraction: String? = null,

    @Column(name = "date_upload", nullable = false) var dateUpload: LocalDateTime = LocalDateTime.now()
)
