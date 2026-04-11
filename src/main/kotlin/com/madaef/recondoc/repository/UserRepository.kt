package com.madaef.recondoc.repository

import com.madaef.recondoc.entity.AppUser
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<AppUser, Long> {
    fun findByEmail(email: String): AppUser?
    fun existsByEmail(email: String): Boolean
}
