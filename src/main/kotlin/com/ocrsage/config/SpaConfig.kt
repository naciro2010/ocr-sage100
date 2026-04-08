package com.ocrsage.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * Serves the React SPA from Spring Boot's classpath static resources.
 * All non-API, non-static routes are forwarded to index.html for client-side routing.
 */
@Configuration
class SpaConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(object : PathResourceResolver() {
                override fun getResource(resourcePath: String, location: Resource): Resource? {
                    val requested = ClassPathResource("static/$resourcePath")
                    // If the file exists (JS, CSS, images, etc.) serve it directly
                    // Otherwise serve index.html for SPA client-side routing
                    return if (requested.exists() && requested.isReadable) {
                        requested
                    } else {
                        ClassPathResource("static/index.html")
                    }
                }
            })
    }
}
