package com.dfsystems.gridwork.api.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Gives every request an id and makes it visible in three places: the log
 * lines for that request, the response header, and later the problem+json
 * body of any error.
 *
 * If the caller sent X-Request-Id we keep it, so a trace survives a hop
 * between services. If not, we generate one. Either way the value goes into
 * the MDC (mapped diagnostic context, the per thread map that the logging
 * encoder reads) under the key "requestId", and comes back out on the
 * response so a caller can quote it in a bug report.
 *
 * Runs highest precedence so the id exists before anything else logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(HEADER)?.takeIf { it.isNotBlank() }?.take(MAX_LENGTH)
            ?: UUID.randomUUID().toString()

        MDC.put(MDC_KEY, requestId)
        response.setHeader(HEADER, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            // Threads are pooled and reused, so the MDC has to be cleared or
            // the next request on this thread inherits the wrong id.
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"

        // A caller supplied header is untrusted input. Cap it so it cannot
        // bloat every log line for the request.
        private const val MAX_LENGTH = 128
    }
}
