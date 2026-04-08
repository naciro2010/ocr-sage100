package com.madaef.recondoc.repository

import com.madaef.recondoc.entity.AppSetting
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface AppSettingRepository : JpaRepository<AppSetting, Long> {
    fun findBySettingKey(settingKey: String): Optional<AppSetting>
    fun findBySettingKeyStartingWith(prefix: String): List<AppSetting>
}
