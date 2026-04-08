package com.madaef.recondoc.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "app_settings")
class AppSetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "setting_key", nullable = false, unique = true)
    var settingKey: String,

    @Column(name = "setting_value", columnDefinition = "TEXT")
    var settingValue: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }
}
