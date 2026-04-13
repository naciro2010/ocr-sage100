package com.madaef.recondoc.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.madaef.recondoc.dto.dossier.*
import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.*
import com.madaef.recondoc.service.extraction.ClassificationService
import com.madaef.recondoc.service.extraction.ExtractionPrompts
import com.madaef.recondoc.service.extraction.LlmExtractionService
import com.madaef.recondoc.service.validation.ValidationEngine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

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
    private val ocrService: OcrService,
    private val validationEngine: ValidationEngine,
    private val eventPublisher: ApplicationEventPublisher,
    private val progressService: DocumentProgressService,
    private val pdfGenerator: PdfGeneratorService,
    private val entityManager: jakarta.persistence.EntityManager,
    private val resultatRepo: ResultatValidationRepository,
    private val objectMapper: ObjectMapper,
    private val auditLogRepo: AuditLogRepository,
    @Value("\${storage.upload-dir:uploads}") private val uploadDir: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val refCounter = AtomicLong(System.currentTimeMillis() % 100000)

    @Transactional
    fun createDossier(request: CreateDossierRequest): DossierPaiement {
        val seq = refCounter.incrementAndGet()
        val ref = "DOS-${java.time.Year.now().value}-${String.format("%05d", seq % 100000)}"
        val dossier = DossierPaiement(
            reference = ref,
            type = request.type,
            fournisseur = request.fournisseur?.takeIf { it.isNotBlank() },
            description = request.description?.takeIf { it.isNotBlank() }
        )
        val saved = dossierRepo.save(dossier)
        audit(saved.id, "CREATION", "Type: ${request.type}, Fournisseur: ${request.fournisseur}")
        return saved
    }

    @Transactional(readOnly = true)
    fun getDossierFull(id: UUID): DossierPaiement {
        return dossierRepo.findByIdWithAll(id)
            .orElseThrow { NoSuchElementException("Dossier not found: $id") }
    }

    @Transactional(readOnly = true)
    fun getDossier(id: UUID): DossierPaiement {
        return dossierRepo.findById(id).orElseThrow { NoSuchElementException("Dossier not found: $id") }
    }

    @Transactional(readOnly = true)
    fun getDossierResponse(id: UUID): DossierResponse {
        val dossier = getDossierFull(id)
        return buildFullResponse(dossier)
    }

    @Transactional(readOnly = true)
    fun getDossierSummary(id: UUID): DossierSummaryResponse {
        val dossier = getDossier(id)
        val nbDocs = documentRepo.findByDossierId(id).size
        val results = resultatRepo.findByDossierId(id)
        return DossierSummaryResponse(
            id = dossier.id!!, reference = dossier.reference,
            type = dossier.type, statut = dossier.statut,
            fournisseur = dossier.fournisseur, description = dossier.description,
            montantTtc = dossier.montantTtc, montantHt = dossier.montantHt,
            montantTva = dossier.montantTva, montantNetAPayer = dossier.montantNetAPayer,
            dateCreation = dossier.dateCreation, dateValidation = dossier.dateValidation,
            validePar = dossier.validePar, motifRejet = dossier.motifRejet,
            nbDocuments = nbDocs,
            nbChecksConformes = results.count { it.statut == StatutCheck.CONFORME },
            nbChecksTotal = results.size
        )
    }

    @Transactional(readOnly = true)
    fun listDocuments(dossierId: UUID): List<DocumentResponse> {
        return documentRepo.findByDossierId(dossierId).map { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listDocumentsWithData(dossierId: UUID): Map<String, Any?> {
        val dossier = getDossierFull(dossierId)
        return mapOf(
            "documents" to dossier.documents.map { it.toResponse() },
            "factures" to dossier.factures.map { factureToMap(it) },
            "bonCommande" to dossier.bonCommande?.document?.donneesExtraites,
            "contratAvenant" to dossier.contratAvenant?.document?.donneesExtraites,
            "ordrePaiement" to dossier.ordrePaiement?.document?.donneesExtraites,
            "checklistAutocontrole" to dossier.checklistAutocontrole?.document?.donneesExtraites,
            "tableauControle" to dossier.tableauControle?.document?.donneesExtraites,
            "pvReception" to dossier.pvReception?.document?.donneesExtraites,
            "attestationFiscale" to dossier.attestationFiscale?.document?.donneesExtraites,
        )
    }

    @Transactional(readOnly = true)
    fun getValidationResults(dossierId: UUID): List<ValidationResultResponse> {
        return resultatRepo.findByDossierId(dossierId).map { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun getDocumentResponse(dossierId: UUID, docId: UUID): DocumentResponse {
        val doc = documentRepo.findById(docId).orElseThrow { NoSuchElementException("Document not found") }
        require(doc.dossier.id == dossierId) { "Document does not belong to this dossier" }
        return doc.toResponse()
    }

    @Transactional(readOnly = true)
    fun listDossiers(pageable: Pageable): Page<DossierListResponse> {
        return dossierRepo.findAllProjected(pageable).map { row ->
            DossierListResponse(
                id = row[0] as UUID,
                reference = row[1] as String,
                type = if (row[2] is DossierType) row[2] as DossierType else DossierType.valueOf(row[2].toString()),
                statut = if (row[3] is StatutDossier) row[3] as StatutDossier else StatutDossier.valueOf(row[3].toString()),
                fournisseur = row[4] as? String,
                description = row[5] as? String,
                montantTtc = row[6] as? BigDecimal,
                montantNetAPayer = row[7] as? BigDecimal,
                dateCreation = row[8] as java.time.LocalDateTime,
                nbDocuments = (row[9] as Number).toInt(),
                nbChecksConformes = (row[10] as Number).toInt(),
                nbChecksTotal = (row[11] as Number).toInt()
            )
        }
    }

    @Transactional(readOnly = true)
    fun getDashboardStats(): DashboardStatsResponse {
        val rows = dossierRepo.getStatsByStatut()
        var total = 0L
        var totalMontant = BigDecimal.ZERO
        val byStatut = mutableMapOf<String, Long>()

        for (row in rows) {
            val statut = (row[0] as StatutDossier).name
            val count = (row[1] as Number).toLong()
            val montant = row[2] as BigDecimal
            byStatut[statut] = count
            total += count
            totalMontant = totalMontant.add(montant)
        }

        return DashboardStatsResponse(
            total = total,
            brouillons = byStatut["BROUILLON"] ?: 0,
            enVerification = byStatut["EN_VERIFICATION"] ?: 0,
            valides = byStatut["VALIDE"] ?: 0,
            rejetes = byStatut["REJETE"] ?: 0,
            montantTotal = totalMontant
        )
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
        return dossier
    }

    @Transactional
    fun changeStatut(id: UUID, request: ChangeStatutRequest): DossierPaiement {
        val dossier = getDossier(id)
        val allowed = when (dossier.statut) {
            StatutDossier.BROUILLON -> setOf(StatutDossier.EN_VERIFICATION, StatutDossier.VALIDE, StatutDossier.REJETE)
            StatutDossier.EN_VERIFICATION -> setOf(StatutDossier.VALIDE, StatutDossier.REJETE, StatutDossier.BROUILLON)
            StatutDossier.VALIDE -> setOf(StatutDossier.BROUILLON)
            StatutDossier.REJETE -> setOf(StatutDossier.BROUILLON)
        }
        require(request.statut in allowed) {
            "Transition ${dossier.statut} -> ${request.statut} non autorisee. Transitions possibles: $allowed"
        }
        if (request.statut == StatutDossier.VALIDE) {
            val criticalRules = setOf("R04", "R07", "R11", "R01", "R16")
            val blockers = dossier.resultatsValidation
                .filter { it.regle in criticalRules && it.statut == StatutCheck.NON_CONFORME }
                .map { "${it.regle}: ${it.libelle}" }
            require(blockers.isEmpty()) {
                "Validation bloquee — regles critiques non conformes: ${blockers.joinToString("; ")}"
            }
        }
        dossier.statut = request.statut
        if (request.statut == StatutDossier.VALIDE) {
            dossier.dateValidation = LocalDateTime.now()
            dossier.validePar = request.validePar
        }
        if (request.statut == StatutDossier.REJETE) {
            dossier.motifRejet = request.motifRejet
        }
        audit(dossier.id, "CHANGEMENT_STATUT", "Nouveau statut: ${request.statut}")
        return dossier
    }

    @Transactional
    fun deleteDossier(id: UUID) {
        // Cascade delete — child tables first, then parents
        entityManager.createNativeQuery("DELETE FROM ligne_facture WHERE facture_id IN (SELECT id FROM facture WHERE dossier_id = :id)").setParameter("id", id).executeUpdate()
        entityManager.createNativeQuery("DELETE FROM grille_tarifaire WHERE contrat_avenant_id IN (SELECT id FROM contrat_avenant WHERE dossier_id = :id)").setParameter("id", id).executeUpdate()
        entityManager.createNativeQuery("DELETE FROM retenue WHERE op_id IN (SELECT id FROM ordre_paiement WHERE dossier_id = :id)").setParameter("id", id).executeUpdate()
        entityManager.createNativeQuery("DELETE FROM point_controle WHERE checklist_id IN (SELECT id FROM checklist_autocontrole WHERE dossier_id = :id)").setParameter("id", id).executeUpdate()
        entityManager.createNativeQuery("DELETE FROM signataire_checklist WHERE checklist_id IN (SELECT id FROM checklist_autocontrole WHERE dossier_id = :id)").setParameter("id", id).executeUpdate()
        entityManager.createNativeQuery("DELETE FROM point_controle_financier WHERE tableau_controle_id IN (SELECT id FROM tableau_controle WHERE dossier_id = :id)").setParameter("id", id).executeUpdate()
        val directTables = listOf("facture", "bon_commande", "contrat_avenant", "ordre_paiement",
            "checklist_autocontrole", "tableau_controle", "pv_reception", "attestation_fiscale",
            "resultat_validation", "audit_log", "document")
        for (table in directTables) {
            entityManager.createNativeQuery("DELETE FROM $table WHERE dossier_id = :id").setParameter("id", id).executeUpdate()
        }
        entityManager.createNativeQuery("DELETE FROM dossier_paiement WHERE id = :id").setParameter("id", id).executeUpdate()
    }

    @Transactional
    fun uploadDocuments(dossierId: UUID, files: List<MultipartFile>, typeDocument: TypeDocument?): List<DocumentResponse> {
        val dossier = getDossier(dossierId)
        val uploadPath = Path.of(uploadDir, dossierId.toString())
        Files.createDirectories(uploadPath)

        return files.map { file ->
            val fileName = "${System.currentTimeMillis()}_${file.originalFilename}"
            val filePath = uploadPath.resolve(fileName)
            file.transferTo(filePath)

            val doc = Document(
                dossier = dossier,
                typeDocument = typeDocument ?: TypeDocument.INCONNU,
                nomFichier = file.originalFilename ?: "unknown",
                cheminFichier = filePath.toString()
            )
            documentRepo.save(doc)

            // Publish event — processing happens async in DocumentEventListener
            eventPublisher.publishEvent(DocumentUploadedEvent(doc.id!!, dossierId))

            doc.toResponse()
        }.also {
            audit(dossierId, "UPLOAD_DOCUMENTS", "${files.size} document(s)")
        }
    }

    @Transactional
    fun deleteDocument(dossierId: UUID, documentId: UUID) {
        val doc = documentRepo.findById(documentId).orElseThrow { NoSuchElementException("Document not found") }
        if (doc.dossier.id != dossierId) throw NoSuchElementException("Document does not belong to this dossier")
        log.info("Deleting document {} from dossier {}", doc.nomFichier, doc.dossier.reference)
        // Delete file on disk if exists
        try { val path = Path.of(doc.cheminFichier); if (Files.exists(path)) Files.delete(path) } catch (_: Exception) {}
        documentRepo.delete(doc)
        audit(dossierId, "DELETE_DOCUMENT", doc.nomFichier)
    }

    @Transactional
    fun changeDocumentType(documentId: UUID, newType: TypeDocument) {
        val doc = documentRepo.findById(documentId).orElseThrow { NoSuchElementException("Document not found") }
        log.info("Changing document {} type from {} to {}", doc.nomFichier, doc.typeDocument, newType)
        doc.typeDocument = newType
        doc.statutExtraction = StatutExtraction.EN_ATTENTE
        doc.donneesExtraites = null
        doc.erreurExtraction = null
        documentRepo.save(doc)
        processDocument(documentId, skipClassification = true)
    }

    @Transactional
    fun processDocument(documentId: UUID, skipClassification: Boolean = false) {
        val doc = documentRepo.findById(documentId).orElseThrow { NoSuchElementException("Document not found") }

        // Try to extract text: from file first (full OCR cascade), then from stored text
        val path = Path.of(doc.cheminFichier)
        val rawText = if (Files.exists(path)) {
            val result = ocrService.extractWithDetails(Files.newInputStream(path), doc.nomFichier, path)
            log.info("Re-extracted {} chars from {} via {}", result.text.length, doc.nomFichier, result.engine)
            result.text
        } else if (!doc.texteExtrait.isNullOrBlank()) {
            log.info("File gone, using stored text for {} ({} chars)", doc.nomFichier, doc.texteExtrait!!.length)
            doc.texteExtrait!!
        } else {
            log.error("No file and no stored text for {}", doc.nomFichier)
            doc.statutExtraction = StatutExtraction.ERREUR
            doc.erreurExtraction = "Fichier introuvable et aucun texte stocke"
            return
        }

        processDocumentWithText(doc, rawText, skipClassification)
    }

    private fun emitProgress(doc: Document, step: String, statut: String, detail: String? = null) {
        try {
            progressService.emit(doc.dossier.id!!, DocumentProgress(
                documentId = doc.id.toString(),
                nomFichier = doc.nomFichier,
                step = step, statut = statut, detail = detail
            ))
        } catch (_: Exception) {}
    }

    private fun processDocumentWithText(doc: Document, rawText: String, skipClassification: Boolean = false) {
        doc.statutExtraction = StatutExtraction.EN_COURS
        emitProgress(doc, "ocr", "active", "Extraction du texte...")

        try {
            if (rawText.isBlank()) {
                doc.statutExtraction = StatutExtraction.ERREUR
                doc.erreurExtraction = "Aucun texte extrait du document"
                emitProgress(doc, "ocr", "error", "Aucun texte extrait")
                return
            }

            doc.texteExtrait = rawText
            emitProgress(doc, "ocr", "done", "${rawText.length} caracteres")

            emitProgress(doc, "classify", "active", "Classification...")
            val detectedType = if (skipClassification) {
                log.info("Skipping classification for {} (manual type: {})", doc.nomFichier, doc.typeDocument)
                doc.typeDocument
            } else {
                val classified = classificationService.classify(rawText)
                doc.typeDocument = classified
                log.info("Document {} classified as {}", doc.nomFichier, classified)
                classified
            }
            emitProgress(doc, "classify", "done", detectedType.name)

            if (detectedType == TypeDocument.INCONNU) {
                doc.statutExtraction = StatutExtraction.EXTRAIT
                doc.erreurExtraction = "Type de document non reconnu - a classer manuellement"
                emitProgress(doc, "extract", "error", "Type inconnu")
                return
            }

            emitProgress(doc, "extract", "active", "Extraction des donnees...")
            if (llmService.isAvailable) {
                val prompt = getPromptForType(detectedType)
                if (prompt != null) {
                    val jsonText = llmService.callClaude(prompt, rawText)
                    val data = parseLlmResponse(jsonText)
                    if (data != null) {
                        doc.donneesExtraites = data
                        saveExtractedEntity(doc.dossier, doc, detectedType, data)
                    } else {
                        log.warn("Failed to parse LLM response for document {}", doc.nomFichier)
                    }
                }
            }

            doc.statutExtraction = StatutExtraction.EXTRAIT
            doc.erreurExtraction = null
            emitProgress(doc, "extract", "done", "Termine")

            if (detectedType == TypeDocument.FACTURE) {
                updateDossierFromFacture(doc.dossier)
            }
        } catch (e: Exception) {
            log.error("Extraction failed for document {}: {}", doc.nomFichier, e.message, e)
            doc.statutExtraction = StatutExtraction.ERREUR
            doc.erreurExtraction = e.message
        }
    }

    @Transactional
    fun validateDossier(dossierId: UUID): List<ResultatValidation> {
        val dossier = getDossierFull(dossierId)
        dossier.statut = StatutDossier.EN_VERIFICATION
        val results = validationEngine.validate(dossier)
        audit(dossierId, "VALIDATION", "${results.size} regles executees")
        return results
    }

    @Transactional
    fun finalizeDossier(dossierId: UUID, request: FinalizeRequest): Map<String, Any> {
        val dossier = getDossierFull(dossierId)
        log.info("Finalizing dossier {} with {} points", dossier.reference, request.points.size)

        // Generate TC PDF
        val tcPdf = pdfGenerator.generateTC(dossier, request)
        val tcPath = Path.of(uploadDir, dossierId.toString(), "TC_${dossier.reference}.pdf")
        Files.createDirectories(tcPath.parent)
        Files.write(tcPath, tcPdf)
        val tcDoc = Document(
            dossier = dossier, typeDocument = TypeDocument.TABLEAU_CONTROLE,
            nomFichier = "TC_${dossier.reference}.pdf", cheminFichier = tcPath.toString(),
            statutExtraction = StatutExtraction.EXTRAIT
        )
        documentRepo.save(tcDoc)

        // Generate OP PDF
        val opPdf = pdfGenerator.generateOP(dossier, request)
        val opPath = Path.of(uploadDir, dossierId.toString(), "OP_${dossier.reference}.pdf")
        Files.write(opPath, opPdf)
        val opDoc = Document(
            dossier = dossier, typeDocument = TypeDocument.ORDRE_PAIEMENT,
            nomFichier = "OP_${dossier.reference}.pdf", cheminFichier = opPath.toString(),
            statutExtraction = StatutExtraction.EXTRAIT
        )
        documentRepo.save(opDoc)

        // Update dossier status
        dossier.statut = StatutDossier.VALIDE
        dossier.dateValidation = java.time.LocalDateTime.now()
        dossier.validePar = request.signataire

        audit(dossierId, "FINALISATION", "TC + OP generes, signe par ${request.signataire}")

        return mapOf(
            "tcDocId" to tcDoc.id.toString(),
            "opDocId" to opDoc.id.toString(),
            "reference" to dossier.reference
        )
    }

    fun exportTC(dossierId: UUID): ByteArray {
        val dossier = getDossierFull(dossierId)
        // Use checklist autocontrole points if available, otherwise use standard 10 points
        val checklist = dossier.checklistAutocontrole
        val points = if (checklist != null && checklist.points.isNotEmpty()) {
            checklist.points.map { pt ->
                ControlPoint(
                    description = pt.description ?: "Point ${pt.numero}",
                    observation = if (pt.estValide == true) "Conforme" else if (pt.estValide == false) "Non conforme" else "NA",
                    commentaire = pt.observation
                )
            }
        } else {
            // Default 10 TC points
            listOf(
                "Concordance facture / modalites contractuelles / livrables",
                "Verification arithmetique des montants",
                "Respect du delai d'execution des prestations",
                "Modifications / avenants (plafonds et variations)",
                "Application des retenues et penalites",
                "Signatures et visas des personnes habilitees",
                "Conformite reglementaire (ICE, IF, RC, CNSS)",
                "Conformite du RIB contractuel vs facture",
                "Conformite BL / PV de reception",
                "Habilitations des signataires des receptions"
            ).map { ControlPoint(it, "NA", null) }
        }
        return pdfGenerator.generateTC(dossier, FinalizeRequest(
            points = points,
            signataire = dossier.validePar ?: "Non signe"
        ))
    }

    fun exportOP(dossierId: UUID): ByteArray {
        val dossier = getDossierFull(dossierId)
        val defaultRequest = FinalizeRequest(
            points = emptyList(),
            signataire = dossier.validePar ?: "Non signe"
        )
        return pdfGenerator.generateOP(dossier, defaultRequest)
    }

    @Transactional
    fun updateValidationResult(resultId: UUID, updates: Map<String, String>): ResultatValidation {
        val repo = resultatRepo
        val result = repo.findById(resultId).orElseThrow { NoSuchElementException("Result not found") }
        updates["statut"]?.let { newStatut ->
            if (result.statutOriginal == null) result.statutOriginal = result.statut.name
            result.statut = StatutCheck.valueOf(newStatut)
        }
        updates["commentaire"]?.let { result.commentaire = it }
        updates["corrigePar"]?.let { result.corrigePar = it }
        result.dateCorrection = java.time.LocalDateTime.now()
        return repo.save(result)
    }

    private fun parseLlmResponse(jsonText: String): Map<String, Any?>? {
        // Try direct parse first
        try {
            @Suppress("UNCHECKED_CAST")
            return objectMapper.readValue(jsonText, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {}

        // Extract JSON from text (LLM sometimes wraps JSON in explanation)
        val jsonMatch = Regex("\\{[\\s\\S]*\\}").find(jsonText)
        if (jsonMatch != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                return objectMapper.readValue(jsonMatch.value, Map::class.java) as Map<String, Any?>
            } catch (_: Exception) {}
        }

        log.error("Failed to parse LLM JSON: no valid JSON found in response ({} chars)", jsonText.length)
        return null
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
        TypeDocument.CHECKLIST_PIECES -> ExtractionPrompts.CHECKLIST_PIECES
        else -> null
    }

    private fun saveExtractedEntity(dossier: DossierPaiement, doc: Document, type: TypeDocument, data: Map<String, Any?>) {
        try {
            when (type) {
                TypeDocument.FACTURE -> saveFacture(dossier, doc, data)
                TypeDocument.BON_COMMANDE -> saveBonCommande(dossier, doc, data)
                TypeDocument.CONTRAT_AVENANT -> saveContrat(dossier, doc, data)
                TypeDocument.ORDRE_PAIEMENT -> saveOrdrePaiement(dossier, doc, data)
                TypeDocument.CHECKLIST_AUTOCONTROLE -> saveChecklist(dossier, doc, data)
                TypeDocument.TABLEAU_CONTROLE -> saveTableau(dossier, doc, data)
                TypeDocument.PV_RECEPTION -> savePvReception(dossier, doc, data)
                TypeDocument.ATTESTATION_FISCALE -> saveAttestationFiscale(dossier, doc, data)
                TypeDocument.CHECKLIST_PIECES -> log.info("CHECKLIST_PIECES stored in donneesExtraites for dossier {}", dossier.reference)
                else -> log.debug("No entity mapping for type {}", type)
            }
        } catch (e: Exception) {
            log.error("Failed to save extracted entity for type {}: {}", type, e.message)
        }
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
        facture.referenceContrat = data["referenceContrat"] as? String
        facture.periode = data["periode"] as? String
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
        op.montantOperation = toBigDecimal(data["montantOperation"])
        op.referenceFacture = data["referenceFacture"] as? String
        op.referenceBcOuContrat = data["referenceBcOuContrat"] as? String
        op.referenceSage = data["referenceSage"] as? String
        op.conclusionControleur = data["conclusionControleur"] as? String
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
        arfRepo.save(arf)
    }

    private fun updateDossierFromFacture(dossier: DossierPaiement) {
        val facture = factureRepo.findByDossierId(dossier.id!!) ?: return
        dossier.montantTtc = facture.montantTtc
        dossier.montantHt = facture.montantHt
        dossier.montantTva = facture.montantTva
        dossier.fournisseur = dossier.fournisseur ?: facture.fournisseur
    }

    private val dateFormats = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy")
    )

    private fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        for (fmt in dateFormats) {
            try { return LocalDate.parse(s.trim(), fmt) } catch (_: Exception) {}
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
        return try { enumValueOf<T>(value) } catch (_: Exception) { default }
    }

    private fun audit(dossierId: UUID?, action: String, detail: String? = null) {
        auditLogRepo.save(AuditLog(dossierId = dossierId, action = action, detail = detail))
    }

    @Transactional(readOnly = true)
    fun getDocumentFile(dossierId: UUID, documentId: UUID): Pair<String, String> {
        val doc = documentRepo.findById(documentId)
            .orElseThrow { NoSuchElementException("Document not found: $documentId") }
        if (doc.dossier.id != dossierId) throw NoSuchElementException("Document $documentId does not belong to dossier $dossierId")
        val path = Path.of(doc.cheminFichier)
        if (!Files.exists(path)) throw NoSuchElementException("File not found on disk: ${doc.cheminFichier}")
        return Pair(doc.cheminFichier, doc.nomFichier)
    }

    fun getAuditLog(dossierId: UUID): List<AuditLogResponse> {
        return auditLogRepo.findByDossierIdOrderByDateActionDesc(dossierId)
            .map { AuditLogResponse(action = it.action, detail = it.detail, utilisateur = it.utilisateur, dateAction = it.dateAction) }
    }

    @Transactional(readOnly = true)
    fun searchDossiers(statut: StatutDossier?, type: DossierType?, fournisseur: String?, pageable: Pageable): Page<DossierListResponse> {
        return dossierRepo.searchProjected(statut, type, fournisseur, pageable).map { row ->
            DossierListResponse(
                id = row[0] as UUID,
                reference = row[1] as String,
                type = if (row[2] is DossierType) row[2] as DossierType else DossierType.valueOf(row[2].toString()),
                statut = if (row[3] is StatutDossier) row[3] as StatutDossier else StatutDossier.valueOf(row[3].toString()),
                fournisseur = row[4] as? String,
                description = row[5] as? String,
                montantTtc = row[6] as? BigDecimal,
                montantNetAPayer = row[7] as? BigDecimal,
                dateCreation = row[8] as java.time.LocalDateTime,
                nbDocuments = (row[9] as Number).toInt(),
                nbChecksConformes = (row[10] as Number).toInt(),
                nbChecksTotal = (row[11] as Number).toInt()
            )
        }
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
            facture = dossier.factures.firstOrNull()?.let { factureToMap(it) },
            factures = dossier.factures.map { factureToMap(it) },
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
        "documentId" to f.document.id?.toString(),
        "numeroFacture" to f.numeroFacture, "dateFacture" to f.dateFacture?.toString(),
        "fournisseur" to f.fournisseur, "client" to f.client,
        "ice" to f.ice, "identifiantFiscal" to f.identifiantFiscal, "rc" to f.rc, "rib" to f.rib,
        "montantHT" to f.montantHt, "montantTVA" to f.montantTva,
        "tauxTVA" to f.tauxTva, "montantTTC" to f.montantTtc,
        "referenceContrat" to f.referenceContrat, "periode" to f.periode
    )
}
