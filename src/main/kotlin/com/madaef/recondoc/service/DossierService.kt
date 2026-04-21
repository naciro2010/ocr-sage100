package com.madaef.recondoc.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.madaef.recondoc.dto.dossier.*
import com.madaef.recondoc.entity.dossier.*
import com.madaef.recondoc.repository.dossier.*
import com.madaef.recondoc.service.extraction.ClassificationService
import com.madaef.recondoc.service.extraction.ExtractionPrompts
import com.madaef.recondoc.service.extraction.ExtractionQualityService
import com.madaef.recondoc.service.extraction.ExtractionSchemaValidator
import com.madaef.recondoc.service.extraction.LlmExtractionService
import com.madaef.recondoc.service.fournisseur.FournisseurMatchingService
import com.madaef.recondoc.service.storage.DocumentStorage
import com.madaef.recondoc.service.storage.ExtractStorage
import com.madaef.recondoc.service.validation.RuleConfigCache
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
    private val qrCodeService: QrCodeService,
    private val entityManager: jakarta.persistence.EntityManager,
    private val resultatRepo: ResultatValidationRepository,
    private val objectMapper: ObjectMapper,
    private val auditLogRepo: AuditLogRepository,
    private val ruleConfigRepo: RuleConfigRepository,
    private val overrideRepo: DossierRuleOverrideRepository,
    private val ruleConfigCache: RuleConfigCache,
    private val extractStorage: ExtractStorage,
    private val documentStorage: DocumentStorage,
    private val extractionQualityService: ExtractionQualityService,
    private val extractionSchemaValidator: ExtractionSchemaValidator,
    private val fournisseurMatchingService: FournisseurMatchingService,
    @Value("\${storage.upload-dir:uploads}") private val uploadDir: String,
    @Value("\${extraction.min-quality-score:70}") private val minQualityScore: Int,
    @Value("\${extraction.human-review-threshold:60}") private val humanReviewThreshold: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val refCounter = AtomicLong(System.currentTimeMillis() % 100000)

    @jakarta.annotation.PostConstruct
    fun logStorageConfig() {
        val abs = Path.of(uploadDir).toAbsolutePath().normalize()
        val ephemeralHints = listOf("/tmp", "/app/uploads", "uploads")
        val looksEphemeral = ephemeralHints.any { abs.toString() == Path.of(it).toAbsolutePath().normalize().toString() }
        if (looksEphemeral) {
            log.warn("Upload directory resolves to {} which is NOT persistent on Railway. Documents will be lost on every redeploy. Mount a Railway Volume and set STORAGE_DIR to a path inside it (e.g. /app/data/uploads).", abs)
        } else {
            log.info("Upload directory: {} (persistence is the responsibility of the mounted Volume)", abs)
        }
    }

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
    fun getDossierResponse(id: UUID, light: Boolean = false): DossierResponse {
        val dossier = getDossier(id)
        // Load only what we need: documents, factures, validation results
        val documents = documentRepo.findByDossierId(id)
        val factures = factureRepo.findAllByDossierId(id)
        val resultats = resultatRepo.findByDossierId(id)

        // Build donneesExtraites map by type from documents (avoids loading 7 entity tables)
        val byType = documents.groupBy { it.typeDocument }
        fun extractedFor(type: TypeDocument): Map<String, Any?>? =
            if (light) null else byType[type]?.firstOrNull()?.donneesExtraites

        val factureMaps = if (light) emptyList() else factures.map { factureToMap(it) }

        return DossierResponse(
            id = dossier.id!!, reference = dossier.reference,
            type = dossier.type, statut = dossier.statut,
            fournisseur = dossier.fournisseur, description = dossier.description,
            montantTtc = dossier.montantTtc, montantHt = dossier.montantHt,
            montantTva = dossier.montantTva, montantNetAPayer = dossier.montantNetAPayer,
            dateCreation = dossier.dateCreation, dateValidation = dossier.dateValidation,
            validePar = dossier.validePar, motifRejet = dossier.motifRejet,
            documents = documents.map { it.toResponse(includeExtractedData = !light) },
            facture = factureMaps.firstOrNull(),
            factures = factureMaps,
            bonCommande = extractedFor(TypeDocument.BON_COMMANDE),
            contratAvenant = extractedFor(TypeDocument.CONTRAT_AVENANT),
            ordrePaiement = extractedFor(TypeDocument.ORDRE_PAIEMENT),
            checklistAutocontrole = extractedFor(TypeDocument.CHECKLIST_AUTOCONTROLE),
            tableauControle = extractedFor(TypeDocument.TABLEAU_CONTROLE),
            pvReception = extractedFor(TypeDocument.PV_RECEPTION),
            attestationFiscale = extractedFor(TypeDocument.ATTESTATION_FISCALE),
            resultatsValidation = resultats.map { it.toResponse() }
        )
    }

    @Transactional(readOnly = true)
    fun getDocumentExtractedData(dossierId: UUID, documentId: UUID): Map<String, Any?>? {
        val doc = documentRepo.findById(documentId).orElseThrow { NoSuchElementException("Document not found") }
        require(doc.dossier.id == dossierId) { "Document does not belong to this dossier" }
        return doc.donneesExtraites
    }

    @Transactional(readOnly = true)
    fun getDocumentOcrText(dossierId: UUID, documentId: UUID): String? {
        val doc = documentRepo.findById(documentId).orElseThrow { NoSuchElementException("Document not found") }
        require(doc.dossier.id == dossierId) { "Document does not belong to this dossier" }
        return loadStoredText(doc)
    }

    @Transactional(readOnly = true)
    fun getRequiredDocuments(dossierId: UUID): RequiredDocumentsResponse {
        val dossier = getDossier(dossierId)
        val defaults = validationEngine.resolveRequiredDocuments(dossier.type, null)
        val effective = validationEngine.resolveRequiredDocuments(dossier.type, dossier.requiredDocuments)
        return RequiredDocumentsResponse(
            defaults = defaults.map { RequiredDocumentEntry(it.first, it.second) },
            selected = effective.map { it.first },
            isCustom = dossier.requiredDocuments != null
        )
    }

    @Transactional
    fun updateRequiredDocuments(dossierId: UUID, selected: List<TypeDocument>?): RequiredDocumentsResponse {
        val dossier = getDossier(dossierId)
        val previous = dossier.requiredDocuments
        dossier.requiredDocuments = selected?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
        audit(dossierId, "UPDATE_REQUIRED_DOCUMENTS",
            if (dossier.requiredDocuments == null) "Retour aux pieces par defaut"
            else "Pieces personnalisees: ${dossier.requiredDocuments}")
        log.info("Dossier {} required documents: {} -> {}", dossier.reference, previous, dossier.requiredDocuments)
        return getRequiredDocuments(dossierId)
    }

    @Transactional(readOnly = true)
    fun getDossierSummary(id: UUID): DossierSummaryResponse {
        val dossier = dossierRepo.findById(id)
            .orElseThrow { NoSuchElementException("Dossier not found: $id") }
        val nbDocs = documentRepo.countByDossierId(id).toInt()
        val nbConformes = resultatRepo.countByDossierIdAndStatut(id, StatutCheck.CONFORME).toInt()
        val nbTotal = resultatRepo.countByDossierId(id).toInt()
        return DossierSummaryResponse(
            id = dossier.id!!, reference = dossier.reference,
            type = dossier.type, statut = dossier.statut,
            fournisseur = dossier.fournisseur, description = dossier.description,
            montantTtc = dossier.montantTtc, montantHt = dossier.montantHt,
            montantTva = dossier.montantTva, montantNetAPayer = dossier.montantNetAPayer,
            dateCreation = dossier.dateCreation, dateValidation = dossier.dateValidation,
            validePar = dossier.validePar, motifRejet = dossier.motifRejet,
            nbDocuments = nbDocs, nbChecksConformes = nbConformes, nbChecksTotal = nbTotal
        )
    }

    @Transactional(readOnly = true)
    fun listDocuments(dossierId: UUID): List<DocumentResponse> {
        return documentRepo.findByDossierId(dossierId).map { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun listDocumentsWithData(dossierId: UUID): Map<String, Any?> {
        val docs = documentRepo.findByDossierId(dossierId)

        fun docData(type: TypeDocument) = docs.find { it.typeDocument == type }?.donneesExtraites
        val factureDocs = docs.filter { it.typeDocument == TypeDocument.FACTURE }
        val factureDataList = factureDocs.map { doc ->
            val data = doc.donneesExtraites ?: emptyMap()
            data + mapOf("documentId" to doc.id?.toString())
        }

        return mapOf(
            "documents" to docs.map { it.toResponse() },
            "factures" to factureDataList,
            "bonCommande" to docData(TypeDocument.BON_COMMANDE),
            "contratAvenant" to docData(TypeDocument.CONTRAT_AVENANT),
            "ordrePaiement" to docData(TypeDocument.ORDRE_PAIEMENT),
            "checklistAutocontrole" to docData(TypeDocument.CHECKLIST_AUTOCONTROLE),
            "tableauControle" to docData(TypeDocument.TABLEAU_CONTROLE),
            "pvReception" to docData(TypeDocument.PV_RECEPTION),
            "attestationFiscale" to docData(TypeDocument.ATTESTATION_FISCALE),
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

    // In-memory cache for dashboard stats (30s TTL)
    @Volatile private var cachedStats: Pair<Long, DashboardStatsResponse>? = null
    private val statsCacheTtl = 30_000L

    @Transactional(readOnly = true)
    fun getDashboardStats(): DashboardStatsResponse {
        cachedStats?.let { (ts, data) ->
            if (System.currentTimeMillis() - ts < statsCacheTtl) return data
        }
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

        val stats = DashboardStatsResponse(
            total = total,
            brouillons = byStatut["BROUILLON"] ?: 0,
            enVerification = byStatut["EN_VERIFICATION"] ?: 0,
            valides = byStatut["VALIDE"] ?: 0,
            rejetes = byStatut["REJETE"] ?: 0,
            montantTotal = totalMontant
        )
        cachedStats = Pair(System.currentTimeMillis(), stats)
        return stats
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

        var dedupedCount = 0
        val responses = files.map { file ->
            val bytes = file.bytes
            val hash = sha256(bytes)

            // Same file already uploaded in this dossier? Reuse the existing document
            // and skip the OCR + Claude pipeline. Saves cost and avoids duplicate rows.
            val existing = documentRepo.findFirstByDossierIdAndFileHash(dossierId, hash)
            if (existing != null) {
                dedupedCount++
                log.info("Duplicate upload detected (hash={}, doc={}), reusing existing", hash.take(8), existing.id)
                return@map existing.toResponse()
            }

            val originalName = file.originalFilename ?: "unknown"
            val pointer = documentStorage.store(dossierId, originalName, bytes)

            val doc = Document(
                dossier = dossier,
                typeDocument = typeDocument ?: TypeDocument.INCONNU,
                nomFichier = originalName,
                cheminFichier = pointer,
                fileHash = hash
            )
            documentRepo.save(doc)

            // Publish event — processing happens async in DocumentEventListener
            eventPublisher.publishEvent(DocumentUploadedEvent(doc.id!!, dossierId))

            doc.toResponse()
        }
        val newCount = files.size - dedupedCount
        audit(dossierId, "UPLOAD_DOCUMENTS",
            if (dedupedCount > 0) "${newCount} nouveau(x), ${dedupedCount} doublon(s) ignore(s)"
            else "$newCount document(s)")
        return responses
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Accept a ZIP archive and ingest each contained PDF/image as a document of
     * the dossier. Skips manifests, hidden files, and anything bigger than 50 MB
     * (same limit as direct upload). Returns one DocumentResponse per file.
     */
    @Transactional
    fun uploadZip(dossierId: UUID, zipFile: MultipartFile, typeDocument: TypeDocument?): Map<String, Any> {
        val dossier = getDossier(dossierId)

        val accepted = mutableListOf<DocumentResponse>()
        var skipped = 0
        var deduped = 0
        val maxBytes = 50L * 1024 * 1024

        java.util.zip.ZipInputStream(zipFile.inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val basename = name.substringAfterLast('/')
                val isAcceptable = !entry.isDirectory &&
                    !basename.startsWith(".") &&
                    !basename.startsWith("__MACOSX") &&
                    basename.lowercase().let { it.endsWith(".pdf") || it.endsWith(".png") ||
                        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".tif") || it.endsWith(".tiff") }
                if (!isAcceptable) {
                    skipped++
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val bytes = zis.readNBytes(maxBytes.toInt() + 1)
                if (bytes.size > maxBytes) {
                    log.warn("Skipping {} from ZIP: too large ({} bytes)", name, bytes.size)
                    skipped++
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val hash = sha256(bytes)
                val existing = documentRepo.findFirstByDossierIdAndFileHash(dossierId, hash)
                if (existing != null) {
                    deduped++
                    accepted.add(existing.toResponse())
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val pointer = documentStorage.store(dossierId, basename, bytes)
                val doc = Document(
                    dossier = dossier,
                    typeDocument = typeDocument ?: TypeDocument.INCONNU,
                    nomFichier = basename,
                    cheminFichier = pointer,
                    fileHash = hash
                )
                documentRepo.save(doc)
                eventPublisher.publishEvent(DocumentUploadedEvent(doc.id!!, dossierId))
                accepted.add(doc.toResponse())
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        audit(dossierId, "UPLOAD_ZIP",
            "${accepted.size - deduped} nouveau(x), $deduped doublon(s), $skipped ignore(s)")
        return mapOf(
            "documents" to accepted,
            "stats" to mapOf("accepted" to accepted.size, "deduped" to deduped, "skipped" to skipped)
        )
    }

    /**
     * Apply the same status transition to several dossiers at once. Returns a
     * per-dossier outcome so the UI can show a summary (X validated, Y errors).
     */
    @Transactional
    fun bulkChangeStatut(ids: List<UUID>, request: ChangeStatutRequest): List<Map<String, Any?>> {
        return ids.map { id ->
            try {
                changeStatut(id, request)
                mapOf<String, Any?>("id" to id, "ok" to true)
            } catch (e: Exception) {
                mapOf<String, Any?>("id" to id, "ok" to false, "error" to (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /**
     * Compare key fields across the documents of a dossier (Facture, BC, Contrat,
     * OP) to spot inconsistencies at a glance. Returns one row per logical field
     * (montantTTC, ICE, RIB, etc.) with the value from each source and a
     * conflict flag.
     */
    @Transactional(readOnly = true)
    fun compareDocuments(dossierId: UUID): Map<String, Any> {
        val docs = documentRepo.findByDossierId(dossierId)
        fun extracted(type: TypeDocument): Map<String, Any?> =
            docs.firstOrNull { it.typeDocument == type }?.donneesExtraites ?: emptyMap()

        val facture = extracted(TypeDocument.FACTURE)
        val bc = extracted(TypeDocument.BON_COMMANDE)
        val contrat = extracted(TypeDocument.CONTRAT_AVENANT)
        val op = extracted(TypeDocument.ORDRE_PAIEMENT)

        // (display label, key in each source map). Use lookup-with-aliases to
        // tolerate slight naming drift between extracted JSON shapes.
        val rows = listOf(
            "Fournisseur" to listOf("fournisseur", "beneficiaire", "raisonSociale"),
            "ICE" to listOf("ice"),
            "Identifiant fiscal" to listOf("identifiantFiscal", "if"),
            "RIB" to listOf("rib"),
            "Reference facture" to listOf("numeroFacture", "referenceFacture"),
            "Reference BC / contrat" to listOf("reference", "referenceContrat", "referenceBcOuContrat"),
            "Montant HT" to listOf("montantHT"),
            "Montant TVA" to listOf("montantTVA"),
            "Taux TVA" to listOf("tauxTVA"),
            "Montant TTC" to listOf("montantTTC", "montantOperation")
        )

        val sources = mapOf(
            "FACTURE" to facture, "BON_COMMANDE" to bc,
            "CONTRAT_AVENANT" to contrat, "ORDRE_PAIEMENT" to op
        )

        val out = rows.map { (label, keys) ->
            val perSource = sources.mapValues { (_, data) ->
                keys.firstNotNullOfOrNull { k -> data[k]?.takeIf { it.toString().isNotBlank() }?.toString() }
            }
            val distinctNonNull = perSource.values.filterNotNull().map { it.lowercase().trim() }.toSet()
            mapOf(
                "label" to label,
                "values" to perSource,
                "conflict" to (distinctNonNull.size > 1)
            )
        }
        return mapOf(
            "dossierId" to dossierId,
            "rows" to out
        )
    }

    @Transactional
    fun deleteDocument(dossierId: UUID, documentId: UUID) {
        val doc = documentRepo.findById(documentId).orElseThrow { NoSuchElementException("Document not found") }
        if (doc.dossier.id != dossierId) throw NoSuchElementException("Document does not belong to this dossier")
        log.info("Deleting document {} from dossier {}", doc.nomFichier, doc.dossier.reference)

        val docName = doc.nomFichier
        val pointer = doc.cheminFichier
        val extractKey = doc.texteExtraitKey

        clearTypedEntityForDocument(doc)
        doc.dossier.documents.remove(doc)
        documentRepo.delete(doc)
        documentRepo.flush()

        // Storage cleanup best-effort : un fichier deja absent ne doit pas annuler la suppression DB.
        documentStorage.delete(pointer)
        extractKey?.let { extractStorage.delete(it) }

        audit(dossierId, "DELETE_DOCUMENT", docName)
    }

    @Transactional
    fun changeDocumentType(documentId: UUID, newType: TypeDocument) {
        val doc = documentRepo.findById(documentId).orElseThrow { NoSuchElementException("Document not found") }
        val oldType = doc.typeDocument
        if (oldType == newType) {
            log.info("Type unchanged for {} ({}), skipping re-extraction", doc.nomFichier, newType)
            return
        }
        log.info("Changing document {} type from {} to {}", doc.nomFichier, oldType, newType)

        clearTypedEntityForDocument(doc)

        doc.typeDocument = newType
        doc.statutExtraction = StatutExtraction.EN_ATTENTE
        doc.donneesExtraites = null
        doc.erreurExtraction = null
        doc.extractionConfidence = -1.0
        doc.extractionWarnings = null
        doc.extractionQualityScore = null
        doc.missingMandatoryFields = null
        documentRepo.saveAndFlush(doc)
        audit(doc.dossier.id, "CHANGE_DOCUMENT_TYPE", "${doc.nomFichier}: $oldType -> $newType")

        // Re-extraction asynchrone apres commit : si elle echoue, le type reste
        // persiste et l'operateur peut relancer manuellement.
        eventPublisher.publishEvent(DocumentUploadedEvent(documentId, doc.dossier.id!!, skipClassification = true))
    }

    // Retire l'enfant type (Facture/BC/...) avant suppression/reclassement,
    // sinon R20/R14 matcheraient encore des donnees obsoletes.
    private fun clearTypedEntityForDocument(doc: Document) {
        val docId = doc.id ?: return
        val d = doc.dossier
        try {
            when (doc.typeDocument) {
                TypeDocument.FACTURE -> d.factures.firstOrNull { it.document.id == docId }?.let {
                    d.factures.remove(it); factureRepo.delete(it)
                }
                TypeDocument.BON_COMMANDE -> d.bonCommande?.takeIf { it.document.id == docId }?.let {
                    d.bonCommande = null; bcRepo.delete(it)
                }
                TypeDocument.CONTRAT_AVENANT -> d.contratAvenant?.takeIf { it.document.id == docId }?.let {
                    d.contratAvenant = null; contratRepo.delete(it)
                }
                TypeDocument.ORDRE_PAIEMENT -> d.ordrePaiement?.takeIf { it.document.id == docId }?.let {
                    d.ordrePaiement = null; opRepo.delete(it)
                }
                TypeDocument.CHECKLIST_AUTOCONTROLE -> d.checklistAutocontrole?.takeIf { it.document.id == docId }?.let {
                    d.checklistAutocontrole = null; checklistRepo.delete(it)
                }
                TypeDocument.TABLEAU_CONTROLE -> d.tableauControle?.takeIf { it.document.id == docId }?.let {
                    d.tableauControle = null; tableauRepo.delete(it)
                }
                TypeDocument.PV_RECEPTION -> d.pvReception?.takeIf { it.document.id == docId }?.let {
                    d.pvReception = null; pvRepo.delete(it)
                }
                TypeDocument.ATTESTATION_FISCALE -> d.attestationFiscale?.takeIf { it.document.id == docId }?.let {
                    d.attestationFiscale = null; arfRepo.delete(it)
                }
                else -> Unit
            }
            entityManager.flush()
        } catch (e: Exception) {
            log.warn("Cleanup of typed entity for {} ({}) failed: {}", doc.nomFichier, doc.typeDocument, e.message)
        }
    }

    @Transactional
    fun processDocument(documentId: UUID, skipClassification: Boolean = false) {
        val doc = documentRepo.findById(documentId).orElseThrow { NoSuchElementException("Document not found") }

        // Try to extract text: from file first (full OCR cascade), then from stored text
        val path = documentStorage.resolveToLocalPath(doc.cheminFichier)
        val ocrResult = if (path != null && Files.exists(path)) {
            val result = ocrService.extractWithDetails(Files.newInputStream(path), doc.nomFichier, path)
            log.info("Re-extracted {} chars from {} via {}", result.text.length, doc.nomFichier, result.engine)
            result
        } else {
            val fallback = loadStoredText(doc)
            if (!fallback.isNullOrBlank()) {
                log.info("File gone, using stored text for {} ({} chars)", doc.nomFichier, fallback.length)
                OcrService.OcrResult(text = fallback, engine = OcrService.OcrEngine.TIKA)
            } else {
                log.error("No file and no stored text for {}", doc.nomFichier)
                doc.statutExtraction = StatutExtraction.ERREUR
                doc.erreurExtraction = "Fichier introuvable et aucun texte stocke"
                return
            }
        }

        // Persist OCR metadata
        doc.ocrEngine = ocrResult.engine.name
        doc.ocrConfidence = ocrResult.confidence
        doc.ocrPageCount = ocrResult.pageCount

        processDocumentWithText(doc, ocrResult, skipClassification)
    }

    private fun persistExtract(doc: Document, rawText: String) {
        val dossierId = doc.dossier.id!!
        val documentId = doc.id ?: run {
            // Should never happen: processDocument loads a saved Document. Keep the
            // inline fallback to avoid losing data if it does.
            doc.texteExtrait = rawText
            return
        }
        try {
            doc.texteExtraitKey = extractStorage.write(dossierId, documentId, rawText)
            doc.texteExtrait = null
        } catch (e: Exception) {
            log.warn("Failed to offload extract for {}, keeping inline: {}", doc.nomFichier, e.message)
            doc.texteExtrait = rawText
        }
    }

    private fun loadStoredText(doc: Document): String? {
        doc.texteExtraitKey?.let { key ->
            val text = extractStorage.read(key)
            if (!text.isNullOrBlank()) return text
            log.warn("texte_extrait_key {} set on {} but extract missing on storage", key, doc.nomFichier)
        }
        return doc.texteExtrait
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

    private fun processDocumentWithText(doc: Document, ocrResult: OcrService.OcrResult, skipClassification: Boolean = false) {
        val rawText = ocrResult.text
        doc.statutExtraction = StatutExtraction.EN_COURS
        emitProgress(doc, "ocr", "active", "Extraction du texte...")

        // Populate MDC so JSON logs and ClaudeUsage can correlate calls back
        // to the dossier/document without threading context through every API.
        org.slf4j.MDC.put("dossierId", doc.dossier.id.toString())
        org.slf4j.MDC.put("documentId", doc.id.toString())

        try {
            if (rawText.isBlank()) {
                doc.statutExtraction = StatutExtraction.ERREUR
                doc.erreurExtraction = "Aucun texte extrait du document"
                emitProgress(doc, "ocr", "error", "Aucun texte extrait")
                return
            }

            persistExtract(doc, rawText)
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
                    val ocrContext = buildString {
                        append("[OCR: moteur=${ocrResult.engine}")
                        if (ocrResult.confidence > 0) append(", confiance=%.0f%%".format(ocrResult.confidence))
                        if (ocrResult.pageCount > 1) append(", pages=${ocrResult.pageCount}")
                        append(", ${rawText.length} caracteres]\n\n")
                        // Encapsulation defensive : toute instruction presente dans le texte OCR
                        // doit etre traitee comme donnee, pas comme directive (voir COMMON_RULES).
                        append("<document_content>\n")
                        append(rawText)
                        append("\n</document_content>")
                    }
                    var jsonText = llmService.callClaude(prompt, ocrContext)
                    var data = parseLlmResponse(jsonText)

                    // Retry with reinforced prompt if confidence is low
                    if (data != null) {
                        val confidence = (data["_confidence"] as? Number)?.toDouble() ?: -1.0
                        if (confidence in 0.0..0.69) {
                            log.info("Low confidence ({}) for {}, retrying with reinforced prompt", confidence, doc.nomFichier)
                            emitProgress(doc, "extract", "active", "Re-verification (confiance faible)...")
                            val retryPrompt = prompt + "\n\nATTENTION: La premiere extraction avait une confiance de ${(confidence * 100).toInt()}%. " +
                                "Re-verifie chaque champ attentivement. Voici les warnings precedents: " +
                                ((data["_warnings"] as? List<*>)?.joinToString(", ") ?: "aucun") +
                                ". Corrige si possible, sinon mets null et explique dans _warnings."
                            val retryJson = llmService.callClaude(retryPrompt, ocrContext)
                            val retryData = parseLlmResponse(retryJson)
                            if (retryData != null) {
                                val retryConfidence = (retryData["_confidence"] as? Number)?.toDouble() ?: -1.0
                                if (retryConfidence > confidence) {
                                    log.info("Retry improved confidence: {} -> {} for {}", confidence, retryConfidence, doc.nomFichier)
                                    data = retryData
                                }
                            }
                        }
                    }

                    if (data != null) {
                        val schemaResult = extractionSchemaValidator.validate(detectedType, data)
                        if (schemaResult.violations.isNotEmpty()) {
                            log.warn("Schema violations on {} ({}): {}", doc.nomFichier, detectedType,
                                schemaResult.violations.joinToString("; ") { "${it.field}=${it.reason}" })
                        }
                        val finalData = schemaResult.cleanedData
                        val confidence = (finalData["_confidence"] as? Number)?.toDouble() ?: -1.0
                        val warnings = (finalData["_warnings"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                        doc.extractionConfidence = confidence
                        doc.extractionWarnings = if (warnings.isNotEmpty()) warnings.joinToString("||") else null

                        doc.donneesExtraites = finalData
                        saveExtractedEntity(doc.dossier, doc, detectedType, finalData)
                    } else {
                        log.warn("Failed to parse LLM response for document {}", doc.nomFichier)
                    }
                }
            }

            doc.statutExtraction = StatutExtraction.EXTRAIT
            doc.erreurExtraction = null
            val qualityReport = extractionQualityService.applyTo(doc)

            if (qualityReport.score < minQualityScore && llmService.isAvailable) {
                val retryReport = retryExtractionWithReinforcedPrompt(doc, detectedType, rawText, ocrResult, qualityReport)
                if (retryReport != null && retryReport.score < humanReviewThreshold) {
                    doc.statutExtraction = StatutExtraction.REVUE_HUMAINE_REQUISE
                    doc.erreurExtraction = "Score qualite ${retryReport.score} < ${humanReviewThreshold} apres retry. " +
                        "Champs manquants: ${retryReport.missingMandatory.joinToString(", ")}"
                    log.warn("Document {} flagged for human review: score={}, missing={}",
                        doc.nomFichier, retryReport.score, retryReport.missingMandatory)
                }
            } else if (qualityReport.score < humanReviewThreshold) {
                doc.statutExtraction = StatutExtraction.REVUE_HUMAINE_REQUISE
                doc.erreurExtraction = "Score qualite ${qualityReport.score} < ${humanReviewThreshold}, " +
                    "revue humaine requise. Champs manquants: ${qualityReport.missingMandatory.joinToString(", ")}"
            }
            emitProgress(doc, "extract", "done", "Termine (score ${doc.extractionQualityScore})")

            if (detectedType == TypeDocument.FACTURE) {
                updateDossierFromFacture(doc.dossier)
            }
        } catch (e: Exception) {
            log.error("Extraction failed for document {}: {}", doc.nomFichier, e.message, e)
            doc.statutExtraction = StatutExtraction.ERREUR
            doc.erreurExtraction = e.message
        } finally {
            org.slf4j.MDC.remove("dossierId")
            org.slf4j.MDC.remove("documentId")
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
    fun rerunRule(dossierId: UUID, regle: String): List<ResultatValidation> {
        val dossier = getDossierFull(dossierId)
        val results = validationEngine.rerunRule(dossier, regle)
        audit(dossierId, "RERUN_RULE", "Regle $regle relancee (+ ${results.size - 1} dependances)")
        return results
    }

    @Transactional(readOnly = true)
    fun getRuleConfig(dossierId: UUID): Map<String, Any> {
        val globals = ruleConfigCache.listGlobal().associate { it.regle to it.enabled }
        val overrides = ruleConfigCache.listOverrides(dossierId).associate { it.regle to it.enabled }
        return mapOf("global" to globals, "overrides" to overrides)
    }

    @Transactional
    fun updateDossierRuleConfig(dossierId: UUID, rules: Map<String, Boolean>) {
        for ((regle, enabled) in rules) {
            val existing = overrideRepo.findByDossierIdAndRegle(dossierId, regle)
            if (existing != null) {
                existing.enabled = enabled
                ruleConfigCache.saveOverride(existing)
            } else {
                ruleConfigCache.saveOverride(DossierRuleOverride(dossierId = dossierId, regle = regle, enabled = enabled))
            }
        }
        audit(dossierId, "RULE_CONFIG", "Config regles modifiee: $rules")
    }

    @Transactional(readOnly = true)
    fun getGlobalRuleConfig(): List<Map<String, Any>> {
        return ruleConfigCache.listGlobal().map { mapOf("regle" to it.regle, "enabled" to it.enabled) }
    }

    @Transactional
    fun updateGlobalRuleConfig(rules: Map<String, Boolean>) {
        for ((regle, enabled) in rules) {
            val existing = ruleConfigRepo.findByRegle(regle)
            if (existing != null) {
                existing.enabled = enabled
                existing.updatedAt = java.time.LocalDateTime.now()
                ruleConfigCache.saveGlobal(existing)
            } else {
                ruleConfigCache.saveGlobal(RuleConfig(regle = regle, enabled = enabled))
            }
        }
    }

    @Transactional
    fun finalizeDossier(dossierId: UUID, request: FinalizeRequest): Map<String, Any> {
        val dossier = getDossierFull(dossierId)
        log.info("Finalizing dossier {} with {} points", dossier.reference, request.points.size)

        // Emit progress so the frontend knows finalization started
        try {
            progressService.emit(dossierId, DocumentProgress(
                documentId = "finalize", nomFichier = "Finalisation",
                step = "tc", statut = "active", detail = "Generation du Tableau de Controle..."
            ))
        } catch (_: Exception) {}

        // Generate TC PDF
        val tcPdf = pdfGenerator.generateTC(dossier, request)
        val tcPointer = documentStorage.store(dossierId, "TC_${dossier.reference}.pdf", tcPdf)
        val tcDoc = Document(
            dossier = dossier, typeDocument = TypeDocument.TABLEAU_CONTROLE,
            nomFichier = "TC_${dossier.reference}.pdf", cheminFichier = tcPointer,
            statutExtraction = StatutExtraction.EXTRAIT
        )
        documentRepo.save(tcDoc)

        try {
            progressService.emit(dossierId, DocumentProgress(
                documentId = "finalize", nomFichier = "Finalisation",
                step = "op", statut = "active", detail = "Generation de l'Ordre de Paiement..."
            ))
        } catch (_: Exception) {}

        // Generate OP PDF
        val opPdf = pdfGenerator.generateOP(dossier, request)
        val opPointer = documentStorage.store(dossierId, "OP_${dossier.reference}.pdf", opPdf)
        val opDoc = Document(
            dossier = dossier, typeDocument = TypeDocument.ORDRE_PAIEMENT,
            nomFichier = "OP_${dossier.reference}.pdf", cheminFichier = opPointer,
            statutExtraction = StatutExtraction.EXTRAIT
        )
        documentRepo.save(opDoc)

        // Update dossier status
        dossier.statut = StatutDossier.VALIDE
        dossier.dateValidation = java.time.LocalDateTime.now()
        dossier.validePar = request.signataire

        audit(dossierId, "FINALISATION", "TC + OP generes, signe par ${request.signataire}")

        try {
            progressService.emit(dossierId, DocumentProgress(
                documentId = "finalize", nomFichier = "Finalisation",
                step = "done", statut = "done", detail = "Finalisation terminee"
            ))
        } catch (_: Exception) {}

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
        updates["valeurTrouvee"]?.let { result.valeurTrouvee = it }
        updates["valeurAttendue"]?.let { result.valeurAttendue = it }
        updates["detail"]?.let { result.detail = it }
        updates["documentIds"]?.let { result.documentIds = it.ifBlank { null } }
        result.dateCorrection = java.time.LocalDateTime.now()
        return repo.save(result)
    }

    /**
     * Atomic: correct a result (valeurs/commentaire/statut), then re-run its rule
     * and dependencies so impacted controls are re-evaluated in one round-trip.
     */
    @Transactional
    fun correctAndRerun(dossierId: UUID, resultId: UUID, updates: Map<String, String>): List<ResultatValidation> {
        val result = resultatRepo.findById(resultId).orElseThrow { NoSuchElementException("Result not found") }
        val regle = result.regle
        updateValidationResult(resultId, updates)
        val dossier = getDossierFull(dossierId)
        val rerun = validationEngine.rerunRule(dossier, regle)
        audit(dossierId, "CORRECT_RERUN", "Regle $regle corrigee et relancee (${rerun.size} resultats)")
        return rerun
    }

    private fun retryExtractionWithReinforcedPrompt(
        doc: Document,
        type: TypeDocument,
        rawText: String,
        ocrResult: OcrService.OcrResult,
        initialReport: com.madaef.recondoc.service.extraction.QualityReport
    ): com.madaef.recondoc.service.extraction.QualityReport? {
        val basePrompt = getPromptForType(type) ?: return null
        val missingFields = initialReport.missingMandatory
        if (missingFields.isEmpty()) return null

        val reinforcedPrompt = basePrompt + "\n\nATTENTION: L'extraction initiale a obtenu un score qualite de ${initialReport.score}/100. " +
            "Les champs OBLIGATOIRES suivants n'ont pas ete trouves ou sont invalides: ${missingFields.joinToString(", ")}. " +
            "Relis attentivement le document pour les retrouver. Si malgre ca un champ reste introuvable, laisse-le a null " +
            "et explique dans _warnings pourquoi. NE JAMAIS inventer une valeur."

        val ocrContext = buildString {
            append("[OCR: moteur=${ocrResult.engine}")
            if (ocrResult.confidence > 0) append(", confiance=%.0f%%".format(ocrResult.confidence))
            if (ocrResult.pageCount > 1) append(", pages=${ocrResult.pageCount}")
            append(", ${rawText.length} caracteres]\n\n")
            append("<document_content>\n")
            append(rawText)
            append("\n</document_content>")
        }

        return try {
            emitProgress(doc, "extract", "active", "Re-extraction (score ${initialReport.score} < ${minQualityScore})...")
            val retryJson = llmService.callClaude(reinforcedPrompt, ocrContext)
            val retryData = parseLlmResponse(retryJson) ?: return null
            val validated = extractionSchemaValidator.validate(type, retryData).cleanedData

            doc.donneesExtraites = validated
            doc.extractionConfidence = (validated["_confidence"] as? Number)?.toDouble() ?: -1.0
            val warnings = (validated["_warnings"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            doc.extractionWarnings = if (warnings.isNotEmpty()) warnings.joinToString("||") else null
            saveExtractedEntity(doc.dossier, doc, type, validated)
            val retryReport = extractionQualityService.applyTo(doc)
            log.info("Retry extraction for {}: score {} -> {}", doc.nomFichier, initialReport.score, retryReport.score)
            retryReport
        } catch (e: Exception) {
            log.warn("Reinforced retry failed for {}: {}", doc.nomFichier, e.message)
            null
        }
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
                val estValide = com.madaef.recondoc.service.validation.ValidationEngine.parseBooleanish(p["estValide"])
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
        val path = documentStorage.resolveToLocalPath(doc.cheminFichier)
        if (path == null || !Files.exists(path)) {
            log.warn("File missing: pointer={} storage.upload-dir={}", doc.cheminFichier, uploadDir)
            throw NoSuchElementException("Fichier introuvable (stockage distant indisponible ou volume local non persistant). Re-uploadez le document ou verifiez la configuration du stockage.")
        }
        return Pair(path.toString(), doc.nomFichier)
    }

    /**
     * Optional fast-path for browsers: if the storage backend supports presigned
     * URLs (S3), return one so the PDF is streamed directly from the bucket
     * without going through the Spring controller. Saves bandwidth and latency.
     * Returns null for filesystem storage — caller falls back to the byte-stream
     * endpoint.
     */
    @Transactional(readOnly = true)
    fun getDocumentPresignedUrl(dossierId: UUID, documentId: UUID): String? {
        val doc = documentRepo.findById(documentId)
            .orElseThrow { NoSuchElementException("Document not found: $documentId") }
        if (doc.dossier.id != dossierId) throw NoSuchElementException("Document $documentId does not belong to dossier $dossierId")
        return documentStorage.presignGet(doc.cheminFichier)
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
