package com.ocrsage.controller

import com.ocrsage.service.ErpConnectorFactory
import com.ocrsage.service.ErpType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/settings")
class SettingsController(
    private val erpConnectorFactory: ErpConnectorFactory,
    @Value("\${erp.active:SAGE_1000}") private val activeErpType: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/erp")
    fun getErpSettings(): ErpSettingsResponse {
        val erpType = try {
            ErpType.valueOf(activeErpType.uppercase())
        } catch (e: IllegalArgumentException) {
            ErpType.SAGE_1000
        }
        return ErpSettingsResponse(
            erpType = erpType.name,
            configured = true
        )
    }

    @PostMapping("/erp")
    fun saveErpSettings(@RequestBody request: ErpSettingsRequest): ErpSettingsResponse {
        log.info("ERP settings update requested: {}", request.erpType)
        return ErpSettingsResponse(
            erpType = request.erpType,
            configured = true
        )
    }

    @PostMapping("/erp/test")
    fun testErpConnection(@RequestBody request: ErpTestRequest): ErpTestResponse {
        val erpType = try {
            ErpType.valueOf(request.erpType.uppercase())
        } catch (e: IllegalArgumentException) {
            return ErpTestResponse(success = false, message = "Type ERP inconnu: ${request.erpType}")
        }

        return try {
            val connector = erpConnectorFactory.getConnector(erpType)
            val success = connector.testConnection()
            ErpTestResponse(
                success = success,
                message = if (success) "Connexion reussie a ${erpType.name}" else "Echec de connexion a ${erpType.name}"
            )
        } catch (e: Exception) {
            log.warn("ERP connection test failed for {}: {}", erpType, e.message)
            ErpTestResponse(success = false, message = "Erreur: ${e.message}")
        }
    }
}

data class ErpSettingsRequest(val erpType: String, val configured: Boolean = true)
data class ErpSettingsResponse(val erpType: String, val configured: Boolean)
data class ErpTestRequest(val erpType: String)
data class ErpTestResponse(val success: Boolean, val message: String)
