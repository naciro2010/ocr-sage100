package com.madaef.recondoc.service

import com.madaef.recondoc.entity.Invoice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Common interface for all ERP connectors.
 * Each Sage ERP variant implements this to provide invoice synchronization.
 */
interface ErpConnector {
    fun syncInvoice(invoice: Invoice): SageSyncResult
    fun testConnection(): Boolean
}

/**
 * Supported ERP systems.
 */
enum class ErpType {
    SAGE_1000,
    SAGE_X3,
    SAGE_50
}

/**
 * Factory that returns the appropriate ERP connector based on application configuration.
 * Uses the strategy pattern to decouple invoice processing from ERP-specific logic.
 */
@Service
class ErpConnectorFactory(
    private val sage1000Service: Sage1000Service,
    private val sageX3Service: SageX3Service,
    private val sage50Service: Sage50Service,
    @Value("\${erp.active:SAGE_1000}") private val activeType: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns the connector for the currently configured ERP type.
     */
    fun getConnector(): ErpConnector {
        val erpType = resolveErpType(activeType)
        log.debug("Resolving ERP connector for type: {}", erpType)
        return getConnector(erpType)
    }

    /**
     * Returns the connector for a specific ERP type.
     */
    fun getConnector(erpType: ErpType): ErpConnector {
        return when (erpType) {
            ErpType.SAGE_1000 -> sage1000Service
            ErpType.SAGE_X3 -> sageX3Service
            ErpType.SAGE_50 -> sage50Service
        }
    }

    /**
     * Syncs an invoice using the currently active ERP connector.
     */
    fun syncInvoice(invoice: Invoice): SageSyncResult {
        val connector = getConnector()
        log.info("Syncing invoice {} via {} connector", invoice.invoiceNumber, activeType)
        return connector.syncInvoice(invoice)
    }

    /**
     * Tests the connection for the currently active ERP.
     */
    fun testConnection(): Boolean {
        val connector = getConnector()
        log.info("Testing connection for {} connector", activeType)
        return connector.testConnection()
    }

    /**
     * Lists all available ERP types.
     */
    fun availableTypes(): List<ErpType> = ErpType.entries

    private fun resolveErpType(value: String): ErpType {
        return try {
            ErpType.valueOf(value.uppercase().replace("-", "_"))
        } catch (e: IllegalArgumentException) {
            log.warn("Unknown ERP type '{}', falling back to SAGE_1000", value)
            ErpType.SAGE_1000
        }
    }
}
