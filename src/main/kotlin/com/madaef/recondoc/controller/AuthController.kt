package com.madaef.recondoc.controller

import com.madaef.recondoc.entity.AppUser
import com.madaef.recondoc.entity.UserRole
import com.madaef.recondoc.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*

data class LoginRequest(val email: String, val password: String)
data class RegisterRequest(val email: String, val password: String, val nom: String, val role: String = "OPERATEUR")
data class UserResponse(val id: Long, val email: String, val nom: String, val role: String)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val userRepo: UserRepository,
    private val encoder: PasswordEncoder
) {

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): ResponseEntity<Any> {
        val user = userRepo.findByEmail(req.email)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Email ou mot de passe incorrect"))
        if (!encoder.matches(req.password, user.password))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Email ou mot de passe incorrect"))
        if (!user.actif)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Compte desactive"))
        return ResponseEntity.ok(user.toResponse())
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody req: RegisterRequest): UserResponse {
        require(!userRepo.existsByEmail(req.email)) { "Email deja utilise" }
        val user = userRepo.save(AppUser(
            email = req.email,
            password = encoder.encode(req.password),
            nom = req.nom,
            role = try { UserRole.valueOf(req.role) } catch (_: Exception) { UserRole.OPERATEUR }
        ))
        return user.toResponse()
    }

    @GetMapping("/me")
    fun me(): ResponseEntity<Any> {
        val auth = SecurityContextHolder.getContext().authentication
        if (auth == null || !auth.isAuthenticated || auth.name == "anonymousUser")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Non authentifie"))
        val user = userRepo.findByEmail(auth.name)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Utilisateur introuvable"))
        return ResponseEntity.ok(user.toResponse())
    }

    @GetMapping("/users")
    fun listUsers(): List<UserResponse> = userRepo.findAll().map { it.toResponse() }

    private fun AppUser.toResponse() = UserResponse(id!!, email, nom, role.name)
}
