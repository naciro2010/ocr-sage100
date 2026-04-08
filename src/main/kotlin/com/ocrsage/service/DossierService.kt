package com.ocrsage.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ocrsage.dto.dossier.*
import com.ocrsage.entity.dossier.*
import com.ocrsage.repository.dossier.*
import com.ocrsage.service.extraction.ClassificationService
import com.ocrsage.service.extraction.ExtractionPrompts
import com.ocrsage.service.extraction.LlmExtractionService
import com.ocrsage.service.validation.ValidationEngine
import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.math.BigDecimal

@Service
class DossierService(
    private val dossierRepo: DossierRepository,
    private val documentRepo: DocumentRepository,
    private val factureRepo: FactureRepository,
    private val bcRepo: BonCommandeRepository,
    private val contratRepo: ContratAvenantRepository,
    private val opRepo: OrdrePaiementRepository,
    private val checklistRepo: ChecklistAutocontroleRepository,
    private val tableauRepo: TableauControleRepository,
    private val pvRepo: PvReceptionRepository,
    private val arfRepo: AttestationFiscaleRepository,
    private val classificationService: ClassificationService,
    private val llmService: LlmExtractionService,
    private val validationEngine: ValidationEngine,
    private val objectMapper: ObjectMapper,
    @Value("\${storage.upload-dir:uploads}") private val uploadDir: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tika = Tika()
    private var nextRef = System.currentTimeMillis()

    @Transactional
    fun createDossier(request: CreateDossierRequest): DossierPaiement {
        val ref = "DOSSIER-${java.time.Year.now().value}-${String.format("%04d", nextRef++ % 10000)}"
        val dossier = DossierPaiement(
            reference = ref,
            type = request.type,
            fournisseur = request.fournisseur,
            description = request.description
        )
        return dossierRepo.save(dossier)
    }

    @Transactional(readOnly = true)
    fun getDossier(id: UUID): DossierPaiement {
        return dossierRepo.findById(id).orElseThrow { NoSuchElementException("Dossier not found: $id") }
    }

    @Transactional(readOnly = true)
    fun getDossierResponse(id: UUID): DossierResponse {
        val dossier = getDossier(id)
        return buildFullResponse(dossier)
    }

    @Transactional(readOnly = true)
    fun listDossiers(pageable: Pageable): Page<DossierListResponse> {
        return dossierRepo.findAll(pageable).map { it.toListResponse() }
    }

    @Transactional
    fun updateDossier(id: UUID, request: UpdateDossierRequest): DossierPaiement {
        val dossier = getDossier(id)
        request.fournisseur?.let { dossier.fournisseur = it }
        request.description?.let { dossier.description = it }
        request.montantTtc?.let { dossier.montantTtc = it }
        request.montantHt?.let { dossier.montantHt = it }
        request.montantTva?.let { dossier.montantTva = it }
        request.montantNetAPayer?.let { dossier.montantNetAPayer = it }
        return dossierRepo.save(dossier)
    }

    @Transactional
    fun changeStatut(id: UUID, request: ChangeStatutRequest): DossierPaiement {
        val dossier = getDossier(id)
        dossier.statut = request.statut
        if (request.statut == StatutDossier.VALIDE) {
            dossier.dateValidation = LocalDateTime.now()
            dossier.validePar = request.validePar
        }
        if (request.statut == StatutDossier.REJETE) {
            dossier.motifRejet = request.motifRejet
        }
        return dossierRepo.save(dossier)
    }

    @Transactional
    fun deleteDossier(id: UUID) {
        dossierRepo.deleteById(id)
    }

    @Transactional
    fun uploadDocuments(dossierId: UUID, files: List<MultipartFile>, typeDocument: TypeDocument?): List<Document> {
        val dossier = getDossier(dossierId)
        val uploadPath = Path.of(uploadDir, dossierId.toString())
        Files.createDirectories(uploadPath)

        return files.map { file ->
            val fileName = "${System.currentTimeMillis()}_${file.originalFilename}"
            val filePath = uploadPath.resolve(fileName)
            file.transferTo(filePath)

            val doc = Document(
                dossier = dossier,
                typeDocument = typeDocument ?: TypeDocument.FACTURE, // will be reclassified
                nomFichier = file.originalFilename ?: "unknown",
                cheminFichier = filePath.toString()
            )
            documentRepo.save(doc)
        }
    }

    @Transactional
    fun processDocument(documentId: UUID) {
        val doc = documentRepo.findById(documentId).orElseThrow { NoSuchElementException("Document not found") }
        doc.statutExtraction = StatutExtraction.EN_COURS
        documentRepo.save(doc)

        try {
            // Step 1: Extract text with Tika
            val rawText = Files.newInputStream(Path.of(doc.cheminFichier)).use { tika.parseToString(it) }
            doc.texteExtrait = rawText

            // Step 2: Classify document
            val detectedType = classificationService.classify(rawText)
            doc.typeDocument = detectedType
            log.info("Document {} classified as {}", doc.nomFichier, detectedType)

            // Step 3: Extract structured data with LLM
            if (llmService.isAvailable) {
                val prompt = getPromptForType(detectedType)
                if (prompt != null) {
                    val jsonText = llmService.callClaude(prompt, rawText)
                    @Suppress("UNCHECKED_CAST")
                    val data = objectMapper.readValue(jsonText, Map::class.java) as Map<String, Any?>
                    doc.donneesExtraites = data

                    // Step 4: Create typed entity from extracted data
                    saveExtractedEntity(doc.dossier, doc, detectedType, data)
                }
            }

            doc.statutExtraction = StatutExtraction.EXTRAIT
            doc.erreurExtraction = null

            // Update dossier amounts from facture
            if (detectedType == TypeDocument.FACTURE) {
                updateDossierFromFacture(doc.dossier)
            }
        } catch (e: Exception) {
            log.error("Extraction failed for document {}: {}", doc.nomFichier, e.message, e)
            doc.statutExtraction = StatutExtraction.ERREUR
            doc.erreurExtraction = e.message
        }

        documentRepo.save(doc)
    }

    @Transactional
    fun validateDossier(dossierId: UUID): List<ResultatValidation> {
        val dossier = getDossier(dossierId)
        dossier.statut = StatutDossier.EN_VERIFICATION
        dossierRepo.save(dossier)
        return validationEngine.validate(dossier)
    }

    private fun getPromptForType(type: TypeDocument): String? = when (type) {
        TypeDocument.FACTURE -> ExtractionPrompts.FACTURE
        TypeDocument.BON_COMMANDE -> ExtractionPrompts.BON_COMMANDE
        TypeDocument.ORDRE_PAIEMENT -> ExtractionPrompts.ORDRE_PAIEMENT
        TypeDocument.CONTRAT_AVENANT -> ExtractionPrompts.CONTRAT_AVENANT
        TypeDocument.CHECKLIST_AUTOCONTROLE -> ExtractionPrompts.CHECKLIST_AUTOCONTROLE
        TypeDocument.TABLEAU_CONTROLE -> ExtractionPrompts.TABLEAU_CONTROLE
        TypeDocument.PV_RECEPTION -> ExtractionPrompts.PV_RECEPTION
        TypeDocument.ATTESTATION_FISCALE -> ExtractionPrompts.ATTESTATION_FISCALE
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun saveExtractedEntity(dossier: DossierPaiement, doc: Document, type: TypeDocument, data: Map<String, Any?>) {
        when (type) {
            TypeDocument.FACTURE -> {
                val existing = factureRepo.findByDossierId(dossier.id!!)
                val facture = existing ?: Facture(dossier = dossier, document = doc)
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
                facture.referenceContrat = data["referenceContrat"] as? String
                facture.periode = data["periode"] as? String
                factureRepo.save(facture)
            }
            TypeDocument.BON_COMMANDE -> {
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
                bcRepo.save(bc)
            }
            TypeDocument.ORDRE_PAIEMENT -> {
                val existing = opRepo.findByDossierId(dossier.id!!)
                val op = existing ?: OrdrePaiement(dossier = dossier, document = doc)
                op.numeroOp = data["numeroOp"] as? String
                op.dateEmission = parseDate(data["dateEmission"] as? String)
                op.emetteur = data["emetteur"] as? String
                op.natureOperation = data["natureOperation"] as? String
                op.description = data["description"] as? String
                op.beneficiaire = data["beneficiaire"] as? String
                op.rib = data["rib"] as? String
                op.banque = data["banque"] as? String
                op.montantOperation = toBigDecimal(data["montantOperation"])
                op.referenceFacture = data["referenceFacture"] as? String
                op.referenceBcOuContrat = data["referenceBcOuContrat"] as? String
                op.referenceSage = data["referenceSage"] as? String
                op.conclusionControleur = data["conclusionControleur"] as? String
                // Retenues
                val retList = data["retenues"] as? List<Map<String, Any?>>
                if (retList != null) {
                    op.retenues.clear()
                    for (r in retList) {
                        op.retenues.add(Retenue(
                            ordrePaiement = op,
                            type = try { TypeRetenue.valueOf(r["type"] as? String ?: "AUTRE") } catch (_: Exception) { TypeRetenue.AUTRE },
                            articleCgi = r["articleCGI"] as? String,
                            base = toBigDecimal(r["base"]),
                            taux = toBigDecimal(r["taux"]),
                            montant = toBigDecimal(r["montant"])
                        ))
                    }
                }
                opRepo.save(op)
            }
            TypeDocument.CHECKLIST_AUTOCONTROLE -> {
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
                        cl.points.add(PointControle(
                            checklist = cl,
                            numero = (p["numero"] as? Number)?.toInt() ?: 0,
                            estValide = p["estValide"] as? Boolean,
                            observation = p["observation"] as? String
                        ))
                    }
                }
                checklistRepo.save(cl)
            }
            TypeDocument.TABLEAU_CONTROLE -> {
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
                            observation = p["observation"] as? String,
                            commentaire = p["commentaire"] as? String
                        ))
                    }
                }
                tableauRepo.save(tc)
            }
            TypeDocument.PV_RECEPTION -> {
                val existing = pvRepo.findByDossierId(dossier.id!!)
                val pv = existing ?: PvReception(dossier = dossier, document = doc)
                pv.titre = data["titre"] as? String
                pv.dateReception = parseDate(data["dateReception"] as? String)
                pv.referenceContrat = data["referenceContrat"] as? String
                pv.periodeDebut = parseDate(data["periodeDebut"] as? String)
                pv.periodeFin = parseDate(data["periodeFin"] as? String)
                @Suppress("UNCHECKED_CAST")
                pv.prestations = (data["prestations"] as? List<*>)?.joinToString(",")
                pv.signataireMadaef = data["signataireMadaef"] as? String
                pv.signataireFournisseur = data["signataireFournisseur"] as? String
                pvRepo.save(pv)
            }
            TypeDocument.ATTESTATION_FISCALE -> {
                val existing = arfRepo.findByDossierId(dossier.id!!)
                val arf = existing ?: AttestationFiscale(dossier = dossier, document = doc)
                arf.numero = data["numero"] as? String
                arf.dateEdition = parseDate(data["dateEdition"] as? String)
                arf.raisonSociale = data["raisonSociale"] as? String
                arf.identifiantFiscal = data["identifiantFiscal"] as? String
                arf.ice = data["ice"] as? String
                arf.rc = data["rc"] as? String
                arf.estEnRegle = data["estEnRegle"] as? Boolean
                arfRepo.save(arf)
            }
            else -> log.debug("No entity mapping for type {}", type)
        }
    }

    private fun updateDossierFromFacture(dossier: DossierPaiement) {
        val facture = factureRepo.findByDossierId(dossier.id!!) ?: return
        dossier.montantTtc = facture.montantTtc
        dossier.montantHt = facture.montantHt
        dossier.montantTva = facture.montantTva
        dossier.fournisseur = dossier.fournisseur ?: facture.fournisseur
        dossierRepo.save(dossier)
    }

    private fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        return try { LocalDate.parse(s) } catch (_: Exception) { null }
    }

    private fun toBigDecimal(v: Any?): BigDecimal? = when (v) {
        is Number -> BigDecimal(v.toString())
        is String -> v.toBigDecimalOrNull()
        else -> null
    }

    fun buildFullResponse(dossier: DossierPaiement): DossierResponse {
        return DossierResponse(
            id = dossier.id!!, reference = dossier.reference,
            type = dossier.type, statut = dossier.statut,
            fournisseur = dossier.fournisseur, description = dossier.description,
            montantTtc = dossier.montantTtc, montantHt = dossier.montantHt,
            montantTva = dossier.montantTva, montantNetAPayer = dossier.montantNetAPayer,
            dateCreation = dossier.dateCreation, dateValidation = dossier.dateValidation,
            validePar = dossier.validePar, motifRejet = dossier.motifRejet,
            documents = dossier.documents.map { it.toResponse() },
            facture = dossier.facture?.let { factureToMap(it) },
            bonCommande = dossier.bonCommande?.document?.donneesExtraites,
            contratAvenant = dossier.contratAvenant?.document?.donneesExtraites,
            ordrePaiement = dossier.ordrePaiement?.document?.donneesExtraites,
            checklistAutocontrole = dossier.checklistAutocontrole?.document?.donneesExtraites,
            tableauControle = dossier.tableauControle?.document?.donneesExtraites,
            pvReception = dossier.pvReception?.document?.donneesExtraites,
            attestationFiscale = dossier.attestationFiscale?.document?.donneesExtraites,
            resultatsValidation = dossier.resultatsValidation.map { it.toResponse() }
        )
    }

    private fun factureToMap(f: Facture): Map<String, Any?> = mapOf(
        "numeroFacture" to f.numeroFacture, "dateFacture" to f.dateFacture?.toString(),
        "fournisseur" to f.fournisseur, "client" to f.client,
        "ice" to f.ice, "identifiantFiscal" to f.identifiantFiscal, "rc" to f.rc, "rib" to f.rib,
        "montantHT" to f.montantHt, "montantTVA" to f.montantTva,
        "tauxTVA" to f.tauxTva, "montantTTC" to f.montantTtc,
        "referenceContrat" to f.referenceContrat, "periode" to f.periode
    )
}
