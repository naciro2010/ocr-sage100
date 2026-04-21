package com.madaef.recondoc.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

data class DocumentUploadedEvent(
    val documentId: UUID,
    val dossierId: UUID,
    // true lors d'un reclassement manuel : preserver le type deja pose.
    val skipClassification: Boolean = false
)

@Component
class DocumentEventListener(
    private val dossierService: DossierService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener
    fun onDocumentUploaded(event: DocumentUploadedEvent) {
        log.info("Processing document {} (dossier {}, skipClassification={}) async",
            event.documentId, event.dossierId, event.skipClassification)
        try {
            dossierService.processDocument(event.documentId, skipClassification = event.skipClassification)
        } catch (e: Exception) {
            log.error("Async processing failed for document {}: {}", event.documentId, e.message, e)
        }
    }
}
