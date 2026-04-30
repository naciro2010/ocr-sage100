package com.madaef.recondoc.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Configuration
@EnableAsync
class AsyncConfig(
    @Value("\${async.executor.virtual:true}") private val useVirtualThreads: Boolean,
) {

    /**
     * Executor utilise par toutes les methodes annotees `@Async` (notamment
     * [com.madaef.recondoc.service.DocumentEventListener] qui orchestre la
     * chaine OCR + classification + extraction Claude apres l'upload).
     *
     * Par defaut sur Java 25 : virtual thread per task.
     *  - Pas de pool fini ni de queue : chaque event spawn un virtual thread.
     *    L'OS gere les VT (millions possibles), le scheduler JVM les park
     *    sur les ports I/O bloquants (Claude API, OCR Tesseract, queries DB).
     *  - Pas de risque de saturation Claude : LlmExtractionService est
     *    deja protege par Resilience4j (`@RateLimiter` + `@Bulkhead`
     *    + `@CircuitBreaker` "claude") qui regule independamment du nombre
     *    de threads appelants.
     *  - Trade-off : pas de back-pressure executor-level. Si un test ou un
     *    benchmark veut revenir a un pool plateforme borne, basculer
     *    `async.executor.virtual=false`.
     */
    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): Executor =
        if (useVirtualThreads) {
            Executors.newVirtualThreadPerTaskExecutor()
        } else {
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 5
                maxPoolSize = 20
                queueCapacity = 100
                setThreadNamePrefix("ocr-async-")
                setWaitForTasksToCompleteOnShutdown(true)
                setAwaitTerminationSeconds(60)
                initialize()
            }
        }
}
