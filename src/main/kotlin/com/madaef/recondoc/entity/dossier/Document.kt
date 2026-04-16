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

    // Legacy inline OCR text. Kept nullable for backward compatibility with rows
    // written before V14. New documents store the text in external storage and
    // reference it via texteExtraitKey; this column is left null.
    @Column(name = "texte_extrait", columnDefinition = "TEXT") var texteExtrait: String? = null,

    // Pointer into ExtractStorage (filesystem key or S3 object key). Resolved on
    // demand, never serialized back to clients.
    @Column(name = "texte_extrait_key", length = 500) var texteExtraitKey: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "donnees_extraites", columnDefinition = "jsonb")
    var donneesExtraites: Map<String, Any?>? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "statut_extraction", nullable = false)
    var statutExtraction: StatutExtraction = StatutExtraction.EN_ATTENTE,

    @Column(name = "erreur_extraction", columnDefinition = "TEXT") var erreurExtraction: String? = null,

    @Column(name = "date_upload", nullable = false) var dateUpload: LocalDateTime = LocalDateTime.now(),

    @Column(name = "ocr_engine") var ocrEngine: String? = null,
    @Column(name = "ocr_confidence") var ocrConfidence: Double = -1.0,
    @Column(name = "ocr_page_count") var ocrPageCount: Int = 1,
    @Column(name = "extraction_confidence") var extractionConfidence: Double = -1.0,
    @Column(name = "extraction_warnings", columnDefinition = "TEXT") var extractionWarnings: String? = null
)
