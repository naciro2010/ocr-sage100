package com.ocrsage.config

import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import javax.sql.DataSource

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter(
    private val dataSource: DataSource
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger("http.requests")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val start = System.currentTimeMillis()
        val method = request.method
        val uri = request.requestURI
        val query = request.queryString?.let { "?$it" } ?: ""

        val poolInfo = getPoolInfo()
        log.info(">>> {} {}{} [pool: {}]", method, uri, query, poolInfo)

        try {
            filterChain.doFilter(request, response)
        } finally {
            val duration = System.currentTimeMillis() - start
            val status = response.status
            val poolAfter = getPoolInfo()

            if (duration > 5000) {
                log.warn("<<< {} {}{} {} in {}ms SLOW [pool: {}]", method, uri, query, status, duration, poolAfter)
            } else {
                log.info("<<< {} {}{} {} in {}ms [pool: {}]", method, uri, query, status, duration, poolAfter)
            }
        }
    }

    private fun getPoolInfo(): String {
        return try {
            val hikari = dataSource as? HikariDataSource
                ?: return "non-hikari"
            val pool = hikari.hikariPoolMXBean ?: return "pool-not-ready"
            "active=${pool.activeConnections}/${hikari.maximumPoolSize},idle=${pool.idleConnections},waiting=${pool.threadsAwaitingConnection}"
        } catch (e: Exception) {
            "pool-error"
        }
    }
}
