package com.madaef.recondoc.service.dossier

import com.madaef.recondoc.entity.dossier.AttestationFiscale
import com.madaef.recondoc.entity.dossier.BonCommande
import com.madaef.recondoc.entity.dossier.ChecklistAutocontrole
import com.madaef.recondoc.entity.dossier.ContratAvenant
import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.Facture
import com.madaef.recondoc.entity.dossier.GrilleTarifaire
import com.madaef.recondoc.entity.dossier.LigneFacture
import com.madaef.recondoc.entity.dossier.OrdrePaiement
import com.madaef.recondoc.entity.dossier.Periodicite
import com.madaef.recondoc.entity.dossier.PointControle
import com.madaef.recondoc.entity.dossier.PointControleFinancier
import com.madaef.recondoc.entity.dossier.PvReception
import com.madaef.recondoc.entity.dossier.Retenue
import com.madaef.recondoc.entity.dossier.SignataireChecklist
import com.madaef.recondoc.entity.dossier.TableauControle
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.entity.dossier.TypeRetenue
import com.madaef.recondoc.repository.dossier.AttestationFiscaleRepository
import com.madaef.recondoc.repository.dossier.BonCommandeRepository
import com.madaef.recondoc.repository.dossier.ChecklistAutocontroleRepository
import com.madaef.recondoc.repository.dossier.ContratAvenantRepository
import com.madaef.recondoc.repository.dossier.FactureRepository
import com.madaef.recondoc.repository.dossier.OrdrePaiementRepository
import com.madaef.recondoc.repository.dossier.PvReceptionRepository
import com.madaef.recondoc.repository.dossier.TableauControleRepository
import com.madaef.recondoc.service.QrCodeService
import com.madaef.recondoc.service.fournisseur.FournisseurMatchingService
import com.madaef.recondoc.service.storage.DocumentStorage
import com.madaef.recondoc.service.validation.parseBooleanish
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Persistance des entites tapees deduites a partir des donnees extraites
 * par Claude (FACTURE, BON_COMMANDE, CONTRAT_AVENANT, ORDRE_PAIEMENT,
 * CHECKLIST_AUTOCONTROLE, TABLEAU_CONTROLE, PV_RECEPTION, ATTESTATION_FISCALE).
 *
 * Extrait de [DossierService] pour limiter le couplage : ces methodes
 * dependent uniquement des repositories d'entites typees, du storage
 * (pour le scan QR de l'attestation), du matching fournisseur et du
 * scanner QR DGI. Elles ne participent ni a la classification, ni a la
 * validation, ni a l'export.
 *
 * Pas de [@Transactional] : on herite de la transaction du caller
 * (DossierService.processDocumentWithText et autres). Cela evite de
 * decouper accidentellement l'unite de travail Hibernate (la transaction
 * ouverte par le caller doit englober la persistance + les eventuels
 * `audit` ou `eventPublisher.publishEvent` qui suivent).
 */
@Service
class ExtractedEntityPersistenceService(
    private val factureRepo: FactureRepository,
    private val bcRepo: BonCommandeRepository,
    private val contratRepo: ContratAvenantRepository,
    private val opRepo: OrdrePaiementRepository,
    private val checklistRepo: ChecklistAutocontroleRepository,
    private val tableauRepo: TableauControleRepository,
    private val pvRepo: PvReceptionRepository,
    private val arfRepo: AttestationFiscaleRepository,
    private val qrCodeService: QrCodeService,
    private val documentStorage: DocumentStorage,
    private val fournisseurMatchingService: FournisseurMatchingService,
) {
    private val log = LoggerFactory.getLogger(ExtractedEntityPersistenceService::class.java)

    /**
     * Persiste l'entite typee correspondant a `type` si elle est geree par
     * ce service. Retourne `true` si le type a ete pris en charge,
     * `false` sinon — auquel cas le caller doit gerer le type lui-meme
     * (cas des documents d'engagement : MARCHE, BON_COMMANDE_CADRE,
     * CONTRAT_CADRE, traites par DossierService).
     */
    fun savePrimary(
        dossier: DossierPaiement,
        doc: Document,
        type: TypeDocument,
        data: Map<String, Any?>
    ): Boolean = when (type) {
        TypeDocument.FACTURE -> { saveFacture(dossier, doc, data); true }
        TypeDocument.BON_COMMANDE -> { saveBonCommande(dossier, doc, data); true }
        TypeDocument.CONTRAT_AVENANT -> { saveContrat(dossier, doc, data); true }
        TypeDocument.ORDRE_PAIEMENT -> { saveOrdrePaiement(dossier, doc, data); true }
        TypeDocument.CHECKLIST_AUTOCONTROLE -> { saveChecklist(dossier, doc, data); true }
        TypeDocument.TABLEAU_CONTROLE -> { saveTableau(dossier, doc, data); true }
        TypeDocument.PV_RECEPTION -> { savePvReception(dossier, doc, data); true }
        TypeDocument.ATTESTATION_FISCALE -> { saveAttestationFiscale(dossier, doc, data); true }
        TypeDocument.CHECKLIST_PIECES -> {
            log.info("CHECKLIST_PIECES stored in donneesExtraites for dossier {}", dossier.reference)
            true
        }
        else -> false
    }

    private fun saveFacture(dossier: DossierPaiement, doc: Document, data: Map<String, Any?>) {
        val existing = factureRepo.findByDocumentId(doc.id!!)
        val facture = existing ?: Facture(dossier = dossier, document = doc)
        facture.document = doc
        facture.numeroFacture = data["numeroFacture"] as? String
        facture.dateFacture = parseDate(data["dateFacture"] as? String)
        facture.fournisseur = data["fournisseur"] as? String
        facture.client = data["client"] as? String
        facture.ice = data["ice"] as? String
        facture.identifiantFiscal = data["identifiantFiscal"] as? String
        facture.rc = data["rc"] as? String
        facture.rib = data["rib"] as? String
        facture.montantHt = toBigDecimal(data["montantHT"])
        facture.montantTva = toBigDecimal(data["montantTVA"])
        facture.tauxTva = toBigDecimal(data["tauxTVA"])
        facture.montantTtc = toBigDecimal(data["montantTTC"])
        facture.devise = (data["devise"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        facture.dateReceptionFacture = parseDate(data["dateReceptionFacture"] as? String)
        facture.referenceContrat = data["referenceContrat"] as? String
        facture.periode = data["periode"] as? String

        facture.fournisseur?.takeIf { it.isNotBlank() }?.let { raw ->
            val match = fournisseurMatchingService.findOrCreateCanonical(
                raw, TypeDocument.FACTURE, facture.ice, facture.identifiantFiscal, facture.rib
            )
            facture.fournisseurCanonique = match.canonique
        }

        @Suppress("UNCHECKED_CAST")
        val rawLignes = data["lignes"] as? List<Map<String, Any?>>
        if (rawLignes != null) {
            facture.lignes.clear()
            for (row in rawLignes) {
                val designation = (row["designation"] as? String)?.trim()
                if (designation.isNullOrBlank()) continue
                facture.lignes.add(LigneFacture(
                    facture = facture,
                    codeArticle = (row["codeArticle"] as? String)?.takeIf { it.isNotBlank() },
                    designation = designation,
                    quantite = toBigDecimal(row["quantite"]),
                    unite = (row["unite"] as? String)?.takeIf { it.isNotBlank() },
                    prixUnitaireHt = toBigDecimal(row["prixUnitaireHT"] ?: row["prixUnitaireHt"]),
                    montantTotalHt = toBigDecimal(row["montantTotalHt"] ?: row["montantTotalHT"] ?: row["montantLigneHT"])
                ))
            }
        }

        factureRepo.save(facture)
    }

    private fun saveBonCommande(dossier: DossierPaiement, doc: Document, data: Map<String, Any?>) {
        val existing = bcRepo.findByDossierId(dossier.id!!)
        val bc = existing ?: BonCommande(dossier = dossier, document = doc)
        bc.reference = data["reference"] as? String
        bc.dateBc = parseDate(data["dateBc"] as? String)
        bc.fournisseur = data["fournisseur"] as? String
        bc.objet = data["objet"] as? String
        bc.montantHt = toBigDecimal(data["montantHT"])
        bc.montantTva = toBigDecimal(data["montantTVA"])
        bc.tauxTva = toBigDecimal(data["tauxTVA"])
        bc.montantTtc = toBigDecimal(data["montantTTC"])
        bc.signataire = data["signataire"] as? String
        bc.fournisseur?.takeIf { it.isNotBlank() }?.let { raw ->
            val match = fournisseurMatchingService.findOrCreateCanonical(raw, TypeDocument.BON_COMMANDE)
            bc.fournisseurCanonique = match.canonique
        }
        bcRepo.save(bc)
    }

    @Suppress("UNCHECKED_CAST")
    private fun saveContrat(dossier: DossierPaiement, doc: Document, data: Map<String, Any?>) {
        val existing = contratRepo.findByDossierId(dossier.id!!)
        val ca = existing ?: ContratAvenant(dossier = dossier, document = doc)
        ca.referenceContrat = data["referenceContrat"] as? String
        ca.numeroAvenant = data["numeroAvenant"] as? String
        ca.dateSignature = parseDate(data["dateSignature"] as? String)
        ca.parties = (data["parties"] as? List<*>)?.joinToString(",")
        ca.objet = data["objet"] as? String
        ca.dateEffet = parseDate(data["dateEffet"] as? String)
        val grilles = data["grillesTarifaires"] as? List<Map<String, Any?>>
        if (grilles != null) {
            ca.grillesTarifaires.clear()
            for (g in grilles) {
                ca.grillesTarifaires.add(GrilleTarifaire(
                    contratAvenant = ca,
                    designation = g["designation"] as? String ?: "",
                    prixUnitaireHt = toBigDecimal(g["prixUnitaireHT"]),
                    periodicite = parseEnum(g["periodicite"] as? String, Periodicite.MENSUEL),
                    entite = g["entite"] as? String
                ))
            }
        }
        (ca.parties?.split(",")?.firstOrNull()?.trim())?.takeIf { it.isNotBlank() }?.let { raw ->
            val match = fournisseurMatchingService.findOrCreateCanonical(raw, TypeDocument.CONTRAT_AVENANT)
            ca.fournisseurCanonique = match.canonique
        }
        contratRepo.save(ca)
    }

    @Suppress("UNCHECKED_CAST")
    private fun saveOrdrePaiement(dossier: DossierPaiement, doc: Document, data: Map<String, Any?>) {
        val existing = opRepo.findByDossierId(dossier.id!!)
        val op = existing ?: OrdrePaiement(dossier = dossier, document = doc)
        if (op.document.id == null) op.document = doc
        op.numeroOp = data["numeroOp"] as? String
        op.dateEmission = parseDate(data["dateEmission"] as? String)
        op.emetteur = data["emetteur"] as? String
        op.natureOperation = data["natureOperation"] as? String
        op.description = data["description"] as? String
        op.beneficiaire = data["beneficiaire"] as? String
        op.rib = data["rib"] as? String
        op.banque = data["banque"] as? String
        op.modePaiement = (data["modePaiement"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        op.devise = (data["devise"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        op.signataireOrdonnateur = (data["signataireOrdonnateur"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        op.signataireComptable = (data["signataireComptable"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        op.montantOperation = toBigDecimal(data["montantOperation"])
        op.referenceFacture = data["referenceFacture"] as? String
        op.referenceBcOuContrat = data["referenceBcOuContrat"] as? String
        op.referenceSage = data["referenceSage"] as? String
        op.conclusionControleur = data["conclusionControleur"] as? String
        op.piecesJustificatives = (data["piecesJustificatives"] as? List<*>)?.joinToString("||")
        val retList = data["retenues"] as? List<Map<String, Any?>>
        if (retList != null) {
            op.retenues.clear()
            for (r in retList) {
                op.retenues.add(Retenue(
                    ordrePaiement = op,
                    type = parseEnum(r["type"] as? String, TypeRetenue.AUTRE),
                    articleCgi = r["articleCGI"] as? String,
                    base = toBigDecimal(r["base"]),
                    taux = toBigDecimal(r["taux"]),
                    montant = toBigDecimal(r["montant"])
                ))
            }
        }
        opRepo.save(op)
    }

    @Suppress("UNCHECKED_CAST")
    private fun saveChecklist(dossier: DossierPaiement, doc: Document, data: Map<String, Any?>) {
        val existing = checklistRepo.findByDossierId(dossier.id!!)
        val cl = existing ?: ChecklistAutocontrole(dossier = dossier, document = doc)
        cl.reference = data["reference"] as? String
        cl.nomProjet = data["nomProjet"] as? String
        cl.referenceFacture = data["referenceFacture"] as? String
        cl.prestataire = data["prestataire"] as? String
        val points = data["points"] as? List<Map<String, Any?>>
        if (points != null) {
            cl.points.clear()
            for (p in points) {
                val estValide = parseBooleanish(p["estValide"])
                cl.points.add(PointControle(
                    checklist = cl,
                    numero = (p["numero"] as? Number)?.toInt() ?: 0,
                    description = p["description"] as? String,
                    estValide = estValide,
                    observation = p["observation"] as? String
                ))
            }
        }
        val signataires = data["signataires"] as? List<Map<String, Any?>>
        if (signataires != null) {
            cl.signataires.clear()
            for (s in signataires) {
                cl.signataires.add(SignataireChecklist(
                    checklist = cl,
                    nom = s["nom"] as? String,
                    dateSignature = parseDate(s["date"] as? String),
                    aSignature = s["aSignature"] as? Boolean ?: false
                ))
            }
        }
        checklistRepo.save(cl)
    }

    @Suppress("UNCHECKED_CAST")
    private fun saveTableau(dossier: DossierPaiement, doc: Document, data: Map<String, Any?>) {
        val existing = tableauRepo.findByDossierId(dossier.id!!)
        val tc = existing ?: TableauControle(dossier = dossier, document = doc)
        tc.societeGeree = data["societeGeree"] as? String
        tc.referenceFacture = data["referenceFacture"] as? String
        tc.fournisseur = data["fournisseur"] as? String
        tc.signataire = data["signataire"] as? String
        val pts = data["points"] as? List<Map<String, Any?>>
        if (pts != null) {
            tc.points.clear()
            for (p in pts) {
                tc.points.add(PointControleFinancier(
                    tableauControle = tc,
                    numero = (p["numero"] as? Number)?.toInt() ?: 0,
                    description = p["description"] as? String,
                    observation = p["observation"] as? String,
                    commentaire = p["commentaire"] as? String
                ))
            }
        }
        tableauRepo.save(tc)
    }

    private fun savePvReception(dossier: DossierPaiement, doc: Document, data: Map<String, Any?>) {
        val existing = pvRepo.findByDossierId(dossier.id!!)
        val pv = existing ?: PvReception(dossier = dossier, document = doc)
        pv.titre = data["titre"] as? String
        pv.dateReception = parseDate(data["dateReception"] as? String)
        pv.referenceContrat = data["referenceContrat"] as? String
        pv.periodeDebut = parseDate(data["periodeDebut"] as? String)
        pv.periodeFin = parseDate(data["periodeFin"] as? String)
        pv.prestations = (data["prestations"] as? List<*>)?.joinToString(",")
        pv.signataireMadaef = data["signataireMadaef"] as? String
        pv.signataireFournisseur = data["signataireFournisseur"] as? String
        pvRepo.save(pv)
    }

    private fun saveAttestationFiscale(dossier: DossierPaiement, doc: Document, data: Map<String, Any?>) {
        val existing = arfRepo.findByDossierId(dossier.id!!)
        val arf = existing ?: AttestationFiscale(dossier = dossier, document = doc)
        arf.numero = data["numero"] as? String
        arf.dateEdition = parseDate(data["dateEdition"] as? String)
        arf.raisonSociale = data["raisonSociale"] as? String
        arf.identifiantFiscal = data["identifiantFiscal"] as? String
        arf.ice = data["ice"] as? String
        arf.rc = data["rc"] as? String
        arf.estEnRegle = data["estEnRegle"] as? Boolean
        arf.dateValidite = parseDate(data["dateValidite"] as? String)
        arf.typeAttestation = (data["typeAttestation"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        arf.codeVerification = (data["codeVerification"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        arf.raisonSociale?.takeIf { it.isNotBlank() }?.let { raw ->
            val match = fournisseurMatchingService.findOrCreateCanonical(
                raw, TypeDocument.ATTESTATION_FISCALE, arf.ice, arf.identifiantFiscal, null
            )
            arf.fournisseurCanonique = match.canonique
        }
        scanQrAndPopulate(doc, arf, data)
        arfRepo.save(arf)
    }

    private fun scanQrAndPopulate(doc: Document, arf: AttestationFiscale, data: Map<String, Any?>) {
        val path = documentStorage.resolveToLocalPath(doc.cheminFichier)
        if (path == null || !Files.exists(path)) {
            arf.qrScanError = "Fichier introuvable au moment du scan QR"
            arf.qrScannedAt = LocalDateTime.now()
            return
        }
        val result = qrCodeService.scan(path, doc.nomFichier)
        arf.qrScannedAt = LocalDateTime.now()
        arf.qrPayload = result.primary
        arf.qrCodeExtrait = QrCodeService.extractVerificationCode(result.primary)
        arf.qrHost = QrCodeService.extractHost(result.primary)
        arf.qrScanError = result.error
        // Expose the QR summary alongside the LLM data so the frontend can show
        // everything in one place without a separate endpoint.
        val mutable = data.toMutableMap()
        mutable["_qr"] = mapOf(
            "payload" to arf.qrPayload,
            "codeExtrait" to arf.qrCodeExtrait,
            "host" to arf.qrHost,
            "officialHost" to QrCodeService.isOfficialDgiHost(arf.qrHost),
            "scannedAt" to arf.qrScannedAt?.toString(),
            "error" to arf.qrScanError
        )
        doc.donneesExtraites = mutable
    }

    // === Helpers de parsing prives ===

    private val dateFormats = listOf(
        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy")
    )

    private fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        for (fmt in dateFormats) {
            try {
                return LocalDate.parse(s.trim(), fmt)
            } catch (_: java.time.format.DateTimeParseException) {
                // Format suivant : c'est le but de la liste dateFormats
            }
        }
        return null
    }

    private fun toBigDecimal(v: Any?): BigDecimal? = when (v) {
        is Number -> BigDecimal(v.toString())
        is String -> parseMonetaryAmount(v)
        else -> null
    }

    private fun parseMonetaryAmount(raw: String): BigDecimal? {
        if (raw.isBlank()) return null
        // Strip currency symbols, spaces (including non-breaking), and thousands separators
        var s = raw.trim()
            .replace(Regex("[\\s\\u00A0]+"), "") // all whitespace including non-breaking
            .replace(Regex("[A-Za-z]"), "")       // currency letters (MAD, DH, EUR)
            .replace("'", "")                     // Swiss thousands separator
            .trim()
        if (s.isEmpty()) return null
        // Determine decimal separator: if both , and . exist, last one is decimal
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        if (lastComma > lastDot) {
            // 1.234,56 or 1234,56 → comma is decimal
            s = s.replace(".", "").replace(",", ".")
        } else if (lastDot > lastComma) {
            // 1,234.56 or 1234.56 → dot is decimal
            s = s.replace(",", "")
        }
        // else: only one or neither → standard parse
        return s.toBigDecimalOrNull()
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String?, default: T): T {
        if (value.isNullOrBlank()) return default
        // enumValueOf leve uniquement IllegalArgumentException quand le nom
        // ne correspond a aucune constante. Tout autre throwable doit remonter.
        return try { enumValueOf<T>(value) } catch (_: IllegalArgumentException) { default }
    }
}
