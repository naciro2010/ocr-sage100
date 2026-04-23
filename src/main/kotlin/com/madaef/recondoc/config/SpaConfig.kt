package com.madaef.recondoc.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.filter.ShallowEtagHeaderFilter
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.util.concurrent.TimeUnit

@Configuration
class SpaConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Vite emet des assets hashes (filename.<hash>.js / .css) sous /assets/.
        // On peut donc les marquer immutable pour 1 an : tout changement de
        // contenu change le nom de fichier, le navigateur recupere le nouveau
        // automatiquement via le nouvel index.html.
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

    /**
     * ETag automatique sur toutes les reponses GET / HEAD : un client qui
     * renvoie le bon `If-None-Match` recoit `304 Not Modified` (corps vide).
     * Combine avec compression et Cache-Control, c'est le moyen le moins
     * cher de raccourcir le rendu des pages qui ne changent pas entre 2
     * navigations (navigation back/forward, refresh accidentel).
     */
    @Bean
    fun shallowEtagHeaderFilter(): FilterRegistrationBean<ShallowEtagHeaderFilter> {
        val reg = FilterRegistrationBean(ShallowEtagHeaderFilter())
        reg.addUrlPatterns("/api/dossiers/*", "/api/engagements/*", "/api/fournisseurs/*", "/api/admin/*", "/api/settings/*")
        reg.order = Ordered.HIGHEST_PRECEDENCE + 10
        return reg
    }

    /**
     * Garde-fou pour `index.html`. Vite injecte le nom hashe des assets dans
     * `index.html`, donc s'il etait mis en cache long, le navigateur referait
     * appel a une ancienne version JS apres deploiement -> ecran blanc.
     * On force `no-cache` (revalidation systematique) pour la coquille de
     * l'app, tout en laissant `assets/*` en cache long.
     */
    @Bean
    fun spaShellNoCacheFilter(): FilterRegistrationBean<OncePerRequestFilter> {
        val filter = object : OncePerRequestFilter() {
            override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                val uri = request.requestURI
                val isApi = uri.startsWith("/api/") || uri.startsWith("/actuator/")
                // Le Service Worker DOIT etre revalide a chaque chargement
                // (sinon une nouvelle version du SW deploiee n'est jamais prise
                // en compte par les onglets ouverts). Meme regle pour
                // manifest.json si on en ajoute un.
                val isServiceWorker = uri == "/sw.js" || uri == "/manifest.json"
                val isAsset = !isServiceWorker && (
                    uri.startsWith("/assets/") ||
                    uri.matches(Regex("^/.+\\.(js|css|svg|png|jpg|jpeg|woff2?|ico)$"))
                )
                if (!isApi && !isAsset) {
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, must-revalidate")
                }
                chain.doFilter(request, response)
            }
        }
        val reg = FilterRegistrationBean(filter)
        reg.order = Ordered.LOWEST_PRECEDENCE
        return reg
    }
}
