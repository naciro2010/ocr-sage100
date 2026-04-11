package com.madaef.recondoc.entity

import jakarta.persistence.*

enum class UserRole { ADMIN, CONTROLEUR, OPERATEUR }

@Entity
@Table(name = "app_user")
class AppUser(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(unique = true, nullable = false)
    var email: String,

    @Column(nullable = false)
    var password: String,

    @Column(nullable = false)
    var nom: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: UserRole = UserRole.OPERATEUR,

    @Column(nullable = false)
    var actif: Boolean = true
)
