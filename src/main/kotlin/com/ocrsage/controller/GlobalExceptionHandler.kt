package com.ocrsage.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import java.nio.file.AccessDeniedException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ResponseEntity<ErrorResponse> {
        log.warn("Not found: {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(e.message ?: "Resource not found"))
    }

    @ExceptionHandler(IllegalStateException::class, IllegalArgumentException::class)
    fun handleBadRequest(e: RuntimeException): ResponseEntity<ErrorResponse> {
        log.warn("Bad request: {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(e.message ?: "Bad request"))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ErrorResponse> {
        log.error("File system access denied: {}", e.file)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("Le serveur ne peut pas accéder au répertoire de stockage. Contactez l'administrateur."))
    }

    @ExceptionHandler(MissingServletRequestPartException::class)
    fun handleMissingPart(e: MissingServletRequestPartException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("Paramètre requis manquant : ${e.requestPartName}"))
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(e: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("Le fichier dépasse la taille maximale autorisée (50 Mo)."))
    }

    @ExceptionHandler(org.springframework.dao.QueryTimeoutException::class)
    fun handleQueryTimeout(e: org.springframework.dao.QueryTimeoutException): ResponseEntity<ErrorResponse> {
        log.error("Database query timeout: {}", e.message)
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
            .body(ErrorResponse("La requete a pris trop de temps. Reessayez."))
    }

    @ExceptionHandler(java.sql.SQLTimeoutException::class)
    fun handleSqlTimeout(e: java.sql.SQLTimeoutException): ResponseEntity<ErrorResponse> {
        log.error("SQL timeout: {}", e.message)
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
            .body(ErrorResponse("Timeout base de donnees. Reessayez."))
    }

    @ExceptionHandler(org.springframework.transaction.CannotCreateTransactionException::class)
    fun handlePoolExhausted(e: org.springframework.transaction.CannotCreateTransactionException): ResponseEntity<ErrorResponse> {
        log.error("Connection pool exhausted: {}", e.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse("Le serveur est temporairement surcharge. Reessayez dans quelques secondes."))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected error [{}]: {}", e.javaClass.simpleName, e.message, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("Internal server error: ${e.javaClass.simpleName}"))
    }
}

data class ErrorResponse(val message: String)
