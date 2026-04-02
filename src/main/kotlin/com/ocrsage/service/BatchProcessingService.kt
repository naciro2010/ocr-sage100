package com.ocrsage.service

import com.ocrsage.entity.InvoiceStatus
import com.ocrsage.repository.InvoiceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

@Service
class BatchProcessingService(
    private val invoiceService: InvoiceService,
    private val invoiceRepository: InvoiceRepository,
    private val erpConnectorFactory: ErpConnectorFactory
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val executor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    )

    private val processingQueue = ConcurrentLinkedQueue<QueueItem>()

    /**
     * Process multiple files concurrently.
     */
    fun processBatch(files: List<MultipartFile>): BatchResult {
        log.info("Starting batch processing of {} files", files.size)

        val startTime = System.currentTimeMillis()
        val results = mutableListOf<BatchItemResult>()
        val futures = mutableListOf<CompletableFuture<BatchItemResult>>()

        for (file in files) {
            val queueItem = QueueItem(
                fileName = file.originalFilename ?: "unknown",
                status = QueueItemStatus.QUEUED,
                submittedAt = LocalDateTime.now()
            )
            processingQueue.add(queueItem)

            val future = CompletableFuture.supplyAsync({
                queueItem.status = QueueItemStatus.PROCESSING
                queueItem.startedAt = LocalDateTime.now()

                try {
                    val response = invoiceService.uploadAndProcess(file)
                    queueItem.status = QueueItemStatus.COMPLETED
                    queueItem.completedAt = LocalDateTime.now()
                    queueItem.invoiceId = response.id

                    BatchItemResult(
                        fileName = file.originalFilename ?: "unknown",
                        success = true,
                        invoiceId = response.id,
                        status = response.status
                    )
                } catch (e: Exception) {
                    log.error("Batch processing failed for file {}: {}", file.originalFilename, e.message)
                    queueItem.status = QueueItemStatus.FAILED
                    queueItem.completedAt = LocalDateTime.now()
                    queueItem.errorMessage = e.message

                    BatchItemResult(
                        fileName = file.originalFilename ?: "unknown",
                        success = false,
                        error = e.message
                    )
                }
            }, executor)

            futures.add(future)
        }

        // Wait for all to complete
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        futures.forEach { results.add(it.get()) }

        val elapsed = System.currentTimeMillis() - startTime
        val successCount = results.count { it.success }
        val failureCount = results.count { !it.success }

        log.info("Batch processing completed: {}/{} succeeded in {}ms", successCount, files.size, elapsed)

        // Clean up completed items from queue
        processingQueue.removeIf { it.status == QueueItemStatus.COMPLETED || it.status == QueueItemStatus.FAILED }

        return BatchResult(
            totalFiles = files.size,
            successful = successCount,
            failed = failureCount,
            processingTimeMs = elapsed,
            results = results
        )
    }

    /**
     * Sync multiple invoices to Sage in batch.
     */
    fun batchSyncToSage(invoiceIds: List<Long>, erpType: String): BatchSyncResult {
        log.info("Starting batch Sage sync for {} invoices (ERP type: {})", invoiceIds.size, erpType)

        val results = mutableListOf<BatchSyncItemResult>()
        val futures = mutableListOf<CompletableFuture<BatchSyncItemResult>>()

        for (invoiceId in invoiceIds) {
            val future = CompletableFuture.supplyAsync({
                try {
                    val invoice = invoiceRepository.findById(invoiceId)
                        .orElseThrow { NoSuchElementException("Invoice not found: $invoiceId") }

                    if (invoice.status != InvoiceStatus.READY_FOR_SAGE) {
                        return@supplyAsync BatchSyncItemResult(
                            invoiceId = invoiceId,
                            success = false,
                            error = "Invoice not ready for sync (status: ${invoice.status})"
                        )
                    }

                    val connector = erpConnectorFactory.getConnector(
                        ErpType.valueOf(erpType.uppercase().replace("-", "_"))
                    )
                    val syncResult = connector.syncInvoice(invoice)

                    if (syncResult.success) {
                        invoice.sageSynced = true
                        invoice.sageSyncDate = LocalDateTime.now()
                        invoice.sageReference = syncResult.reference
                        invoice.status = InvoiceStatus.SAGE_SYNCED
                        invoiceRepository.save(invoice)

                        BatchSyncItemResult(
                            invoiceId = invoiceId,
                            success = true,
                            sageReference = syncResult.reference
                        )
                    } else {
                        invoice.status = InvoiceStatus.SAGE_SYNC_FAILED
                        invoice.errorMessage = "Sage sync failed: ${syncResult.error}"
                        invoiceRepository.save(invoice)

                        BatchSyncItemResult(
                            invoiceId = invoiceId,
                            success = false,
                            error = syncResult.error
                        )
                    }
                } catch (e: Exception) {
                    log.error("Batch sync failed for invoice {}: {}", invoiceId, e.message)
                    BatchSyncItemResult(
                        invoiceId = invoiceId,
                        success = false,
                        error = e.message
                    )
                }
            }, executor)

            futures.add(future)
        }

        CompletableFuture.allOf(*futures.toTypedArray()).join()
        futures.forEach { results.add(it.get()) }

        val successCount = results.count { it.success }
        val failureCount = results.count { !it.success }

        log.info("Batch sync completed: {}/{} succeeded", successCount, invoiceIds.size)

        return BatchSyncResult(
            totalInvoices = invoiceIds.size,
            synced = successCount,
            failed = failureCount,
            erpType = erpType,
            results = results
        )
    }

    /**
     * Show current processing queue status.
     */
    fun getProcessingQueue(): List<QueueItem> {
        return processingQueue.toList()
    }
}

// --- Data classes ---

data class BatchResult(
    val totalFiles: Int,
    val successful: Int,
    val failed: Int,
    val processingTimeMs: Long,
    val results: List<BatchItemResult>
)

data class BatchItemResult(
    val fileName: String,
    val success: Boolean,
    val invoiceId: Long? = null,
    val status: String? = null,
    val error: String? = null
)

data class BatchSyncResult(
    val totalInvoices: Int,
    val synced: Int,
    val failed: Int,
    val erpType: String,
    val results: List<BatchSyncItemResult>
)

data class BatchSyncItemResult(
    val invoiceId: Long,
    val success: Boolean,
    val sageReference: String? = null,
    val error: String? = null
)

data class QueueItem(
    val fileName: String,
    @Volatile var status: QueueItemStatus,
    val submittedAt: LocalDateTime,
    @Volatile var startedAt: LocalDateTime? = null,
    @Volatile var completedAt: LocalDateTime? = null,
    @Volatile var invoiceId: Long? = null,
    @Volatile var errorMessage: String? = null
)

enum class QueueItemStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
