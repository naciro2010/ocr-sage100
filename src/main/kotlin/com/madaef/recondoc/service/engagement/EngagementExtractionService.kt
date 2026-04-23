package com.madaef.recondoc.service.engagement

import tools.jackson.databind.ObjectMapper
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.entity.engagement.*
import com.madaef.recondoc.repository.engagement.EngagementRepository
import com.madaef.recondoc.service.OcrService
import com.madaef.recondoc.service.extraction.ClassificationService
import com.madaef.recondoc.service.extraction.ExtractionPrompts
import com.madaef.recondoc.service.extraction.LlmExtractionService
import com.madaef.recondoc.service.storage.DocumentStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Orchestre le flow "upload document contractuel -> Engagement" :
 *   1. Stockage du PDF via DocumentStorage
 *   2. OCR (reuse OcrService)
 *   3. Classification (reuse ClassificationService) -- doit retourner MARCHE,
 *      BON_COMMANDE_CADRE ou CONTRAT_CADRE
 *   4. Extraction Claude avec le prompt dedie au type
 *   5. Upsert Engagement par reference (create si nouveau, update sinon)
 *
 * L'upsert by-reference evite les doublons lors de re-uploads et permet la
 * correction iterative d'un engagement a partir d'une version corrigee du PDF.
 */
@Service
class EngagementExtractionService(
    private val engagementRepo: EngagementRepository,
    private val documentStorage: DocumentStorage,
    private val ocrService: OcrService,
    private val classificationService: ClassificationService,
    private val llmService: LlmExtractionService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class UploadResult(
        val engagementId: UUID,
        val reference: String,
        val typeEngagement: TypeEngagement,
        val created: Boolean,
        val confidence: Double?,
        val warnings: List<String>
    )

    @Transactional
    fun uploadAndExtract(file: MultipartFile): UploadResult {
        require(llmService.isAvailable) { "Cle API Claude non configuree (Settings > IA)" }

        val bytes = file.bytes
        val hash = sha256(bytes)
        val originalName = file.originalFilename ?: "document.pdf"

        // Dedoublonnage : meme fichier deja uploade pour un engagement ?
        val existingByHash = engagementRepo.findFirstBySourceDocumentHash(hash)
        if (existingByHash != null) {
            log.info("Document deja connu (hash={}), engagement {} reutilise",
                hash.take(8), existingByHash.reference)
            return UploadResult(
                engagementId = existingByHash.id!!,
                reference = existingByHash.reference,
                typeEngagement = existingByHash.typeEngagement(),
                created = false,
                confidence = null,
                warnings = listOf("Document deja uploade precedemment : engagement reutilise tel quel")
            )
        }

        // 1. OCR + classification
        val rawText = file.inputStream.use { ocrService.extractText(it, originalName) }
        if (rawText.isBlank()) {
            throw IllegalArgumentException("Impossible d'extraire du texte du document")
        }
        val type = classificationService.classify(rawText)
        if (type !in CONTRACTUAL_TYPES) {
            throw IllegalArgumentException(
                "Document classifie comme $type, pas un document contractuel. " +
                "Utilisez /api/dossiers pour les documents transactionnels."
            )
        }

        // 2. Extraction Claude avec prompt dedie
        val prompt = promptFor(type)
        val wrapped = "<document_content>\n$rawText\n</document_content>"
        val jsonResponse = llmService.callClaude(prompt, wrapped, com.madaef.recondoc.service.extraction.CallKind.EXTRACTION)
        val data = parseJson(jsonResponse)
            ?: throw IllegalStateException("Reponse Claude invalide : JSON non parseable")

        // 3. Upsert engagement
        val reference = (data["reference"] as? String)?.trim()
            ?: throw IllegalStateException("Champ 'reference' manquant dans l'extraction")

        val storagePath = documentStorage.storeEngagementDocument(reference, originalName, bytes)
        val confidence = (data["_confidence"] as? Number)?.toDouble()
        val warnings = (data["_warnings"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        val existing = engagementRepo.findByReference(reference)
        val engagement = if (existing != null) {
            log.info("Mise a jour engagement existant : type={} ref={}", type, reference)
            applyData(existing, data, type)
            existing.sourceDocumentPath = storagePath
            existing.sourceDocumentName = originalName
            existing.sourceDocumentHash = hash
            existing.dateModification = LocalDateTime.now()
            engagementRepo.save(existing)
        } else {
            log.info("Creation engagement : type={} ref={}", type, reference)
            val created = buildNew(type)
            created.reference = reference
            applyData(created, data, type)
            created.sourceDocumentPath = storagePath
            created.sourceDocumentName = originalName
            created.sourceDocumentHash = hash
            engagementRepo.save(created)
        }

        return UploadResult(
            engagementId = engagement.id!!,
            reference = engagement.reference,
            typeEngagement = engagement.typeEngagement(),
            created = existing == null,
            confidence = confidence,
            warnings = warnings
        )
    }

    /**
     * Upsert d'un engagement a partir de donnees deja extraites (pas de OCR/LLM).
     * Utilise par DossierService quand un document contractuel est uploade dans
     * le flow dossier : extraction faite par le pipeline standard, puis ici on
     * cree/met a jour l'engagement et on retourne son id pour rattachement.
     */
    @Transactional
    fun upsertFromExtractedData(
        type: TypeDocument,
        data: Map<String, Any?>,
        sourceDocumentPath: String? = null,
        sourceDocumentName: String? = null,
        sourceDocumentHash: String? = null
    ): Engagement? {
        require(type in CONTRACTUAL_TYPES) { "Type non contractuel : $type" }
        val reference = (data["reference"] as? String)?.trim()
        if (reference.isNullOrBlank()) {
            log.warn("Impossible de creer un engagement : champ reference manquant")
            return null
        }

        val existing = engagementRepo.findByReference(reference)
        val engagement = existing ?: buildNew(type).apply { this.reference = reference }
        applyData(engagement, data, type)
        sourceDocumentPath?.let { engagement.sourceDocumentPath = it }
        sourceDocumentName?.let { engagement.sourceDocumentName = it }
        sourceDocumentHash?.let { engagement.sourceDocumentHash = it }
        if (existing != null) engagement.dateModification = LocalDateTime.now()
        return engagementRepo.save(engagement)
    }

    // === Helpers ===

    private fun promptFor(type: TypeDocument): String = when (type) {
        TypeDocument.MARCHE -> ExtractionPrompts.MARCHE
        TypeDocument.BON_COMMANDE_CADRE -> ExtractionPrompts.BON_COMMANDE_CADRE
        TypeDocument.CONTRAT_CADRE -> ExtractionPrompts.CONTRAT_CADRE
        else -> error("Type non contractuel : $type")
    }

    private fun buildNew(type: TypeDocument): Engagement = when (type) {
        TypeDocument.MARCHE -> EngagementMarche()
        TypeDocument.BON_COMMANDE_CADRE -> EngagementBonCommande()
        TypeDocument.CONTRAT_CADRE -> EngagementContrat()
        else -> error("Type non contractuel : $type")
    }

    private fun applyData(engagement: Engagement, data: Map<String, Any?>, type: TypeDocument) {
        // Champs communs
        (data["objet"] as? String)?.takeIf { it.isNotBlank() }?.let { engagement.objet = it }
        (data["fournisseur"] as? String)?.takeIf { it.isNotBlank() }?.let { engagement.fournisseur = it }
        toBigDecimal(data["montantHt"])?.let { engagement.montantHt = it }
        toBigDecimal(data["montantTva"])?.let { engagement.montantTva = it }
        toBigDecimal(data["tauxTva"])?.let { engagement.tauxTva = it }
        toBigDecimal(data["montantTtc"])?.let { engagement.montantTtc = it }
        toLocalDate(data["dateDocument"])?.let { engagement.dateDocument = it }
        toLocalDate(data["dateSignature"])?.let { engagement.dateSignature = it }
        toLocalDate(data["dateNotification"])?.let { engagement.dateNotification = it }

        // Champs specifiques par type
        when (engagement) {
            is EngagementMarche -> applyMarche(engagement, data)
            is EngagementBonCommande -> applyBc(engagement, data)
            is EngagementContrat -> applyContrat(engagement, data)
        }
    }

    private fun applyMarche(e: EngagementMarche, data: Map<String, Any?>) {
        (data["numeroAo"] as? String)?.takeIf { it.isNotBlank() }?.let { e.numeroAo = it }
        toLocalDate(data["dateAo"])?.let { e.dateAo = it }
        (data["categorie"] as? String)?.let { cat ->
            runCatching { CategorieMarche.valueOf(cat.uppercase()) }.getOrNull()?.let { e.categorie = it }
        }
        (data["delaiExecutionMois"] as? Number)?.toInt()?.let { e.delaiExecutionMois = it }
        toBigDecimal(data["retenueGarantiePct"])?.let { e.retenueGarantiePct = it }
        toBigDecimal(data["cautionDefinitivePct"])?.let { e.cautionDefinitivePct = it }
        toBigDecimal(data["penalitesRetardJourPct"])?.let { e.penalitesRetardJourPct = it }
        (data["revisionPrixAutorisee"] as? Boolean)?.let { e.revisionPrixAutorisee = it }
    }

    private fun applyBc(e: EngagementBonCommande, data: Map<String, Any?>) {
        toBigDecimal(data["plafondMontant"])?.let { e.plafondMontant = it }
        toLocalDate(data["dateValiditeFin"])?.let { e.dateValiditeFin = it }
        toBigDecimal(data["seuilAntiFractionnement"])?.let { e.seuilAntiFractionnement = it }
    }

    private fun applyContrat(e: EngagementContrat, data: Map<String, Any?>) {
        (data["periodicite"] as? String)?.let { p ->
            runCatching { PeriodiciteContrat.valueOf(p.uppercase()) }.getOrNull()?.let { e.periodicite = it }
        }
        toLocalDate(data["dateDebut"])?.let { e.dateDebut = it }
        toLocalDate(data["dateFin"])?.let { e.dateFin = it }
        (data["reconductionTacite"] as? Boolean)?.let { e.reconductionTacite = it }
        (data["preavisResiliationJours"] as? Number)?.toInt()?.let { e.preavisResiliationJours = it }
        (data["indiceRevision"] as? String)?.takeIf { it.isNotBlank() }?.let { e.indiceRevision = it }
    }

    private fun toBigDecimal(v: Any?): BigDecimal? = when (v) {
        null -> null
        is Number -> BigDecimal(v.toString())
        is String -> v.replace(" ", "").replace(",", ".").toBigDecimalOrNull()
        else -> null
    }

    private fun toLocalDate(v: Any?): LocalDate? = (v as? String)?.takeIf { it.isNotBlank() }?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(raw: String): Map<String, Any?>? {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { objectMapper.readValue(cleaned, Map::class.java) as? Map<String, Any?> }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val CONTRACTUAL_TYPES = setOf(
            TypeDocument.MARCHE,
            TypeDocument.BON_COMMANDE_CADRE,
            TypeDocument.CONTRAT_CADRE
        )
    }
}
