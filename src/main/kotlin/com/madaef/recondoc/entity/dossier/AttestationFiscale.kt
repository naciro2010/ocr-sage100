package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime
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
    @Column(name = "date_validite") var dateValidite: LocalDate? = null,

    // Type d'attestation DGI : REGULARITE_FISCALE (la plus courante, R18 6mo
    // B2B / 3mo marche public), ATTESTATION_PAIEMENT (paiement d'un impot
    // specifique), CNSS (regularite sociale, hors scope DGI strict).
    // Sert a R18 pour appliquer la bonne duree de validite, et permet d'eviter
    // de confondre les types qui ont des regles distinctes.
    @Column(name = "type_attestation", length = 30) var typeAttestation: String? = null,

    // "Code de verification sur www.tax.gov.ma" printed under the QR. Read by OCR/LLM.
    @Column(name = "code_verification") var codeVerification: String? = null,
    // Raw payload decoded from the QR code on the document.
    @Column(name = "qr_payload", columnDefinition = "TEXT") var qrPayload: String? = null,
    // Verification code parsed out of qrPayload (query param or hex blob).
    @Column(name = "qr_code_extrait") var qrCodeExtrait: String? = null,
    // Host parsed out of the QR URL — expected to be a tax.gov.ma subdomain.
    @Column(name = "qr_host") var qrHost: String? = null,
    @Column(name = "qr_scanned_at") var qrScannedAt: LocalDateTime? = null,
    @Column(name = "qr_scan_error", columnDefinition = "TEXT") var qrScanError: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_canonique_id")
    var fournisseurCanonique: FournisseurCanonique? = null
)
