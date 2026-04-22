package com.madaef.recondoc.controller

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

    /**
     * FK orpheline ou contrainte d'integrite violee : au lieu de renvoyer un 500
     * generique (qui empechait notamment la suppression des dossiers en erreur
     * quand une FK n'etait pas purgee), on retourne 409 Conflict avec la racine
     * de l'erreur. Cela rend le probleme visible cote UI et journal Railway.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    fun handleDataIntegrity(e: org.springframework.dao.DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) {
            root = root.cause!!
        }
        val detail = root.message ?: e.message ?: "integrity violation"
        log.error("Data integrity violation: {}", detail, e)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("Conflit d'integrite BD : $detail"))
    }

    /**
     * Long-poll / SSE endpoints (DocumentProgressService) end with an async
     * timeout when nothing happens for the configured interval — expected
     * behaviour, not an error. Spring surfaces it to the controller advice
     * once the connection is torn down; swallow it silently.
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException::class)
    fun handleAsyncTimeout(): ResponseEntity<Void> {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException::class)
    fun handleAsyncNotUsable(): ResponseEntity<Void> {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
    }

    @ExceptionHandler(java.io.IOException::class)
    fun handleIo(e: java.io.IOException): ResponseEntity<ErrorResponse> {
        // Client disconnects while streaming produce "Broken pipe" / "ClientAbortException".
        // These are noise, not errors — log once at DEBUG and return a generic 503.
        val msg = e.message ?: ""
        if (msg.contains("Broken pipe", ignoreCase = true) ||
            msg.contains("Connection reset", ignoreCase = true) ||
            e.javaClass.simpleName == "ClientAbortException") {
            log.debug("Client disconnected mid-stream: {}", e.javaClass.simpleName)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse("Connexion interrompue"))
        }
        log.warn("I/O error: {}", msg)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("Erreur I/O"))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected error [{}]: {}", e.javaClass.simpleName, e.message, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("Internal server error: ${e.javaClass.simpleName}"))
    }
}

data class ErrorResponse(val message: String)
