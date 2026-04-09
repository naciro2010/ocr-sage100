package com.madaef.recondoc.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

@Configuration
class SpaConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(object : PathResourceResolver() {
                override fun getResource(resourcePath: String, location: Resource): Resource? {
                    if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                        return null
                    }
                    val requested = ClassPathResource("static/$resourcePath")
                    return if (requested.exists() && requested.isReadable) {
                        requested
                    } else {
                        ClassPathResource("static/index.html")
                    }
                }
            })
    }
}
