package com.madaef.recondoc.controller.dossier

import com.madaef.recondoc.dto.dossier.*
import com.madaef.recondoc.entity.dossier.DossierType
import com.madaef.recondoc.entity.dossier.StatutDossier
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.DocumentProgressService
import com.madaef.recondoc.service.DocumentSearchService
import com.madaef.recondoc.service.DossierService
import com.madaef.recondoc.service.ExcelExportService
import com.madaef.recondoc.service.FinalizeRequest
import com.madaef.recondoc.service.dossier.DossierExportService
import com.madaef.recondoc.service.dossier.DossierRuleConfigService
import com.madaef.recondoc.service.validation.RuleCatalog
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/dossiers")
class DossierController(
    private val dossierService: DossierService,
    private val progressService: DocumentProgressService,
    private val documentSearchService: DocumentSearchService,
    private val excelExportService: ExcelExportService,
    private val dossierRuleConfigService: DossierRuleConfigService,
    private val dossierExportService: DossierExportService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateDossierRequest): DossierResponse {
        val dossier = dossierService.createDossier(request)
        return dossierService.getDossierResponse(dossier.id!!)
    }

    @GetMapping
    fun list(@PageableDefault(size = 20, sort = ["dateCreation"], direction = Sort.Direction.DESC) pageable: Pageable): ResponseEntity<Page<DossierListResponse>> {
        // Cache court (5s) cote client : la liste evolue tres peu en quelques
        // secondes, mais reste sensible aux uploads + statut. Combine avec
        // l'ETag global, on economise la serialisation JSON (304) sur les
        // navigations back/forward et les rafraichissements rapides.
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=5, must-revalidate")
            .body(dossierService.listDossiers(pageable))
    }

    @GetMapping("/stats")
    fun stats(): ResponseEntity<DashboardStatsResponse> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=30")
            .body(dossierService.getDashboardStats())
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
        @RequestParam(required = false, defaultValue = "false") light: Boolean
    ): ResponseEntity<DossierResponse> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3, must-revalidate")
            .body(dossierService.getDossierResponse(id, light))
    }

    @GetMapping("/{id}/documents/{docId}/extract-data")
    fun getDocumentExtractedData(
        @PathVariable id: UUID,
        @PathVariable docId: UUID
    ): ResponseEntity<Map<String, Any?>> {
        val data = dossierService.getDocumentExtractedData(id, docId)
            ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
            .body(data)
    }

    @GetMapping("/{id}/documents/{docId}/ocr-text", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun getDocumentOcrText(
        @PathVariable id: UUID,
        @PathVariable docId: UUID
    ): ResponseEntity<String> {
        val text = dossierService.getDocumentOcrText(id, docId)
            ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .body(text)
    }

    @GetMapping("/{id}/summary")
    fun getSummary(@PathVariable id: UUID): ResponseEntity<DossierSummaryResponse> {
        val summary = dossierService.getDossierSummary(id)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=2")
            .body(summary)
    }

    /**
     * Endpoint "tout-en-un" pour la page Detail. Combine summary + documents
     * + resultats-validation + audit + rule-config. Cote front, ouvrir un
     * dossier passe de 5 GET paralleles a 1 seul roundtrip + 1 seul ETag.
     */
    @GetMapping("/{id}/snapshot")
    fun getSnapshot(@PathVariable id: UUID): ResponseEntity<DossierSnapshotResponse> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3, must-revalidate")
            .body(dossierService.getDossierSnapshot(id))
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateDossierRequest): DossierResponse {
        dossierService.updateDossier(id, request)
        return dossierService.getDossierResponse(id)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        dossierService.deleteDossier(id)
    }

    @PatchMapping("/{id}/statut")
    fun changeStatut(@PathVariable id: UUID, @RequestBody request: ChangeStatutRequest): DossierResponse {
        dossierService.changeStatut(id, request)
        return dossierService.getDossierResponse(id)
    }

    @PostMapping("/{id}/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun uploadDocuments(
        @PathVariable id: UUID,
        @RequestParam("files") files: List<MultipartFile>,
        @RequestParam("type", required = false) type: TypeDocument?
    ): List<DocumentResponse> {
        return dossierService.uploadDocuments(id, files, type)
    }

    @GetMapping("/{id}/documents")
    fun listDocuments(@PathVariable id: UUID): ResponseEntity<Map<String, Any?>> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3, must-revalidate")
            .body(dossierService.listDocumentsWithData(id))
    }

    @PostMapping("/{id}/documents/zip", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun uploadZip(
        @PathVariable id: UUID,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("type", required = false) type: TypeDocument?
    ): Map<String, Any> {
        return dossierService.uploadZip(id, file, type)
    }

    @GetMapping("/{id}/compare")
    fun compareDocuments(@PathVariable id: UUID): Map<String, Any> {
        return dossierService.compareDocuments(id)
    }

    @PostMapping("/bulk/statut")
    fun bulkChangeStatut(@RequestBody body: BulkStatutRequest): List<Map<String, Any?>> {
        return dossierService.bulkChangeStatut(
            body.ids,
            ChangeStatutRequest(statut = body.statut, motifRejet = body.motifRejet, validePar = body.validePar)
        )
    }

    @PostMapping("/{id}/valider")
    fun validate(@PathVariable id: UUID): List<ValidationResultResponse> {
        return dossierService.validateDossier(id).map { it.toResponse() }
    }

    @GetMapping("/{id}/resultats-validation")
    fun getValidationResults(@PathVariable id: UUID): ResponseEntity<List<ValidationResultResponse>> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=5")
            .body(dossierService.getValidationResults(id))
    }

    @PatchMapping("/{id}/validation/{resultId}")
    fun updateValidationResult(
        @PathVariable id: UUID, @PathVariable resultId: UUID,
        @RequestBody body: Map<String, String>
    ): ValidationResultResponse {
        return dossierService.updateValidationResult(resultId, body).toResponse()
    }

    @PostMapping("/{id}/documents/{docId}/reprocess")
    fun reprocessDocument(@PathVariable id: UUID, @PathVariable docId: UUID): DocumentResponse {
        dossierService.processDocument(docId)
        return dossierService.getDocumentResponse(id, docId)
    }

    @PatchMapping("/{id}/documents/{docId}/type")
    fun changeDocumentType(
        @PathVariable id: UUID, @PathVariable docId: UUID,
        @RequestBody body: Map<String, String>
    ): DocumentResponse {
        val newType = TypeDocument.valueOf(body["typeDocument"] ?: throw IllegalArgumentException("typeDocument required"))
        dossierService.changeDocumentType(docId, newType)
        return dossierService.getDocumentResponse(id, docId)
    }

    @DeleteMapping("/{id}/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDocument(@PathVariable id: UUID, @PathVariable docId: UUID) {
        dossierService.deleteDocument(id, docId)
    }

    @GetMapping("/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamEvents(@PathVariable id: UUID): SseEmitter {
        return progressService.subscribe(id)
    }

    @GetMapping("/{id}/audit")
    fun getAudit(@PathVariable id: UUID): ResponseEntity<List<AuditLogResponse>> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
            .body(dossierService.getAuditLog(id))
    }

    @GetMapping("/{id}/documents/{docId}/file")
    fun downloadDocumentFile(
        @PathVariable id: UUID,
        @PathVariable docId: UUID,
        @RequestParam(required = false, defaultValue = "false") redirect: Boolean
    ): ResponseEntity<Resource> {
        // Opt-in redirect path: when ?redirect=true and S3 storage is active,
        // send a 307 to a presigned bucket URL so the browser fetches the PDF
        // directly from the CDN. Saves backend bandwidth for iframe previews.
        // Default remains a byte stream so fetch().blob() downloads stay safe
        // even when the bucket hasn't enabled CORS yet.
        if (redirect) {
            dossierService.getDocumentPresignedUrl(id, docId)?.let { url ->
                return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .header(HttpHeaders.LOCATION, url)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                    .build()
            }
        }

        val (filePath, fileName) = dossierService.getDocumentFile(id, docId)
        val resource = FileSystemResource(filePath)
        val contentType = when {
            fileName.endsWith(".pdf", true) -> MediaType.APPLICATION_PDF
            fileName.endsWith(".png", true) -> MediaType.IMAGE_PNG
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> MediaType.IMAGE_JPEG
            else -> MediaType.APPLICATION_OCTET_STREAM
        }
        return ResponseEntity.ok()
            .contentType(contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}\"")
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
            .body(resource)
    }

    /**
     * When S3 storage is enabled, returns a short-lived presigned URL the browser
     * can GET directly against the bucket — no backend bandwidth consumed. For
     * filesystem storage the response is 204 No Content, meaning "fall back to
     * the byte-streaming endpoint above".
     */
    @GetMapping("/{id}/documents/{docId}/file-url")
    fun documentPresignedUrl(@PathVariable id: UUID, @PathVariable docId: UUID): ResponseEntity<Map<String, String>> {
        val url = dossierService.getDocumentPresignedUrl(id, docId)
            ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(mapOf("url" to url))
    }

    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) statut: StatutDossier?,
        @RequestParam(required = false) type: DossierType?,
        @RequestParam(required = false) fournisseur: String?,
        @PageableDefault(size = 20, sort = ["dateCreation"], direction = Sort.Direction.DESC) pageable: Pageable
    ): ResponseEntity<Page<DossierListResponse>> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=5, must-revalidate")
            .body(dossierService.searchDossiers(statut, type, fournisseur, pageable))
    }

    @GetMapping("/search-documents")
    fun searchDocuments(
        @RequestParam q: String,
        @RequestParam(required = false, defaultValue = "50") limit: Int
    ): List<DocumentSearchService.Hit> {
        return documentSearchService.search(q, limit.coerceIn(1, 200))
    }

    @PostMapping("/{id}/finalize")
    fun finalize(@PathVariable id: UUID, @RequestBody request: FinalizeRequest): Map<String, Any> {
        return dossierExportService.finalizeDossier(id, request)
    }

    @GetMapping("/{id}/export/tc", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun exportTC(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val pdf = dossierExportService.exportTC(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"TC_${id}.pdf\"")
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
            .body(pdf)
    }

    @GetMapping("/{id}/export/op", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun exportOP(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val pdf = dossierExportService.exportOP(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"OP_${id}.pdf\"")
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
            .body(pdf)
    }

    @GetMapping(
        "/{id}/export/excel",
        produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"]
    )
    fun exportExcel(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val dossier = dossierService.getDossierFull(id)
        val xlsx = excelExportService.exportDossier(dossier)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${dossier.reference}.xlsx\"")
            .body(xlsx)
    }

    @PostMapping("/{id}/validation/rerun/{regle}")
    fun rerunRule(@PathVariable id: UUID, @PathVariable regle: String): List<ValidationResultResponse> {
        return dossierService.rerunRule(id, regle).map { it.toResponse() }
    }

    @PostMapping("/{id}/validation/{resultId}/correct-and-rerun")
    fun correctAndRerun(
        @PathVariable id: UUID, @PathVariable resultId: UUID,
        @RequestBody body: Map<String, String>
    ): List<ValidationResultResponse> {
        return dossierService.correctAndRerun(id, resultId, body).map { it.toResponse() }
    }

    @GetMapping("/validation/cascade/{regle}")
    fun getCascadeScope(@PathVariable regle: String): ResponseEntity<Map<String, Any>> {
        // Catalogue statique, recompile avec le backend -> cache long cote
        // client (10 min) avec must-revalidate. L'ETag se chargera de
        // renvoyer 304 si le contenu n'a pas bouge.
        val cascade = RuleCatalog.cascade(regle)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600, must-revalidate")
            .body(mapOf("regle" to regle, "cascade" to cascade, "count" to cascade.size))
    }

    @GetMapping("/rule-catalog")
    fun getRuleCatalog(): ResponseEntity<List<RuleCatalogEntry>> =
        ResponseEntity.ok()
            // Catalogue statique (recompile avec le backend). Long cache cote client
            // + revalidation conditionnelle economisent ~1 roundtrip par chargement de page.
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=600, must-revalidate")
            .body(RuleCatalog.all())

    @GetMapping("/{id}/required-documents")
    fun getRequiredDocuments(@PathVariable id: UUID): ResponseEntity<RequiredDocumentsResponse> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=30, must-revalidate")
            .body(dossierService.getRequiredDocuments(id))
    }

    @PatchMapping("/{id}/required-documents")
    fun updateRequiredDocuments(
        @PathVariable id: UUID,
        @RequestBody body: UpdateRequiredDocumentsRequest
    ): RequiredDocumentsResponse {
        return dossierService.updateRequiredDocuments(id, body.selected)
    }

    @GetMapping("/{id}/rule-config")
    fun getRuleConfig(@PathVariable id: UUID): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=30, must-revalidate")
            .body(dossierRuleConfigService.getRuleConfig(id))
    }

    @PatchMapping("/{id}/rule-config")
    fun updateRuleConfig(@PathVariable id: UUID, @RequestBody body: Map<String, Boolean>): Map<String, Any> {
        dossierRuleConfigService.updateDossierRuleConfig(id, body)
        return dossierRuleConfigService.getRuleConfig(id)
    }

    @GetMapping("/global-rule-config")
    fun getGlobalRuleConfig(): ResponseEntity<List<Map<String, Any>>> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60, must-revalidate")
            .body(dossierRuleConfigService.getGlobalRuleConfig())
    }

    @PatchMapping("/global-rule-config")
    fun updateGlobalRuleConfig(@RequestBody body: Map<String, Boolean>): List<Map<String, Any>> {
        dossierRuleConfigService.updateGlobalRuleConfig(body)
        return dossierRuleConfigService.getGlobalRuleConfig()
    }
}
