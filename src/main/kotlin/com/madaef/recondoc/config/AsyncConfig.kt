package com.madaef.recondoc.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableAsync
class AsyncConfig {

    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 5
        maxPoolSize = 20
        queueCapacity = 100
        setThreadNamePrefix("ocr-async-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(60)
        initialize()
    }
}
