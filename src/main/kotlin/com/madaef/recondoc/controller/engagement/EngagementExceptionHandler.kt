package com.madaef.recondoc.controller.engagement

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Centralise la gestion des exceptions pour tous les controllers du
 * package engagement. Evite la duplication des @ExceptionHandler dans
 * chaque controller.
 *
 * Scope limite via basePackageClasses : n'affecte pas les controllers
 * existants (DossierController et autres gardent leur comportement).
 */
@RestControllerAdvice(basePackageClasses = [EngagementController::class])
class EngagementExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        log.debug("Bad request on engagement endpoint: {}", e.message)
        return ResponseEntity.badRequest().body(ErrorResponse(e.message ?: "Requete invalide"))
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(e: IllegalStateException): ResponseEntity<ErrorResponse> {
        log.debug("Conflict on engagement endpoint: {}", e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(e.message ?: "Conflit"))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ResponseEntity<ErrorResponse> {
        log.debug("Not found on engagement endpoint: {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: "Non trouve"))
    }
}

data class ErrorResponse(val error: String)
