package com.ocrsage.controller

import com.ocrsage.service.ErpConnectorFactory
import com.ocrsage.service.ErpType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/settings")
class SettingsController(
    private val erpConnectorFactory: ErpConnectorFactory
) {

    @GetMapping("/erp")
    fun getErpSettings(): ErpSettingsResponse {
        return ErpSettingsResponse(
            activeType = erpConnectorFactory.getConnector().javaClass.simpleName,
            availableTypes = erpConnectorFactory.availableTypes().map { it.name }
        )
    }

    @PostMapping("/erp")
    fun saveErpSettings(@RequestBody settings: ErpSettingsRequest) {
        ErpType.valueOf(settings.erpType.uppercase().replace("-", "_"))
    }

    @PostMapping("/erp/test")
    fun testErpConnection(@RequestBody request: ErpTestRequest): ErpTestResponse {
        return try {
            val erpType = ErpType.valueOf(request.erpType.uppercase().replace("-", "_"))
            val connector = erpConnectorFactory.getConnector(erpType)
            val success = connector.testConnection()
            ErpTestResponse(
                success = success,
                message = if (success) "Connexion reussie a ${request.erpType}" else "Echec de connexion a ${request.erpType}"
            )
        } catch (e: Exception) {
            ErpTestResponse(success = false, message = "Erreur: ${e.message}")
        }
    }
}

data class ErpSettingsRequest(val erpType: String, val configured: Boolean = true)
data class ErpTestRequest(val erpType: String)
data class ErpTestResponse(val success: Boolean, val message: String)
data class ErpSettingsResponse(val activeType: String, val availableTypes: List<String>)
