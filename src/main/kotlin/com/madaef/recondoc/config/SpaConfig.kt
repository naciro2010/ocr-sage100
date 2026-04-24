package com.madaef.recondoc.config

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.filter.ShallowEtagHeaderFilter
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.util.concurrent.TimeUnit

/**
 * ETag automatique sur les GET JSON courts. Filter wrappe la reponse en
 * memoire pour calculer le hash -> on l'evite explicitement pour :
 *   - SSE (text/event-stream) : le buffering casserait le stream live,
 *   - exports binaires (PDF/Excel) et downloads /file : trop gros pour
 *     etre buffer en memoire, et deja servis avec Cache-Control public,
 *   - resolveDocumentUrl /file-url : 1 redirect, pas la peine.
 */
class JsonOnlyEtagFilter : ShallowEtagHeaderFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return uri.endsWith("/events") ||
            uri.contains("/export/") ||
            uri.endsWith("/file") ||
            uri.endsWith("/file-url") ||
            uri.endsWith("/ocr-text")
    }
}

/**
 * Force `no-cache` sur la coquille SPA (`index.html`) et `sw.js` pour qu'une
 * nouvelle version deployee soit toujours prise en compte. Les assets
 * hashes Vite (`/assets/...`) gardent leur cache long via le resource
 * handler.
 */
class SpaShellNoCacheFilter : OncePerRequestFilter() {

    @Throws(ServletException::class, java.io.IOException::class)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val uri = request.requestURI
        val isApi = uri.startsWith("/api/") || uri.startsWith("/actuator/")
        val isServiceWorker = uri == "/sw.js" || uri == "/manifest.json"
        val isAsset = !isServiceWorker && (
            uri.startsWith("/assets/") ||
                uri.matches(Regex("^/.+\\.(js|css|svg|png|jpg|jpeg|woff2?|ico)$"))
            )
        if (!isApi && !isAsset) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, must-revalidate")
        }
        filterChain.doFilter(request, response)
    }
}

@Configuration
class SpaConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Vite emet des assets hashes (filename.<hash>.js / .css) sous /assets/.
        // Tout changement de contenu change le nom de fichier -> safe a marquer
        // immutable pour 1 an.
        registry.addResourceHandler("/assets/**")
            .addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .resourceChain(true)

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

    @Bean
    fun shallowEtagHeaderFilter(): FilterRegistrationBean<JsonOnlyEtagFilter> {
        val reg = FilterRegistrationBean(JsonOnlyEtagFilter())
        reg.addUrlPatterns(
            "/api/dossiers/*",
            "/api/engagements/*",
            "/api/fournisseurs/*",
            "/api/admin/*",
            "/api/settings/*"
        )
        reg.order = Ordered.HIGHEST_PRECEDENCE + 10
        return reg
    }

    @Bean
    fun spaShellNoCacheFilter(): FilterRegistrationBean<SpaShellNoCacheFilter> {
        val reg = FilterRegistrationBean(SpaShellNoCacheFilter())
        reg.order = Ordered.LOWEST_PRECEDENCE
        return reg
    }
}
