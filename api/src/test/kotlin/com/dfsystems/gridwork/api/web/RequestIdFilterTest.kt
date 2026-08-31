package com.dfsystems.gridwork.api.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * Unit test for the request id contract. No Spring context and no database,
 * so this runs in milliseconds and cannot be broken by infrastructure.
 */
class RequestIdFilterTest {

    private val filter = RequestIdFilter()

    @Test
    fun `keeps a caller supplied request id and echoes it back`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        request.addHeader(RequestIdFilter.HEADER, "caller-supplied-id")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        response.getHeader(RequestIdFilter.HEADER) shouldBe "caller-supplied-id"
    }

    @Test
    fun `generates a request id when the caller sends none`() {
        val response = MockHttpServletResponse()

        filter.doFilter(MockHttpServletRequest("GET", "/actuator/health"), response, MockFilterChain())

        val generated = response.getHeader(RequestIdFilter.HEADER)
        generated shouldNotBe null
        // A UUID string, so the id is unique across replicas without coordination.
        generated!! shouldHaveLength 36
    }

    @Test
    fun `generates a request id when the caller sends a blank one`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        request.addHeader(RequestIdFilter.HEADER, "   ")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        response.getHeader(RequestIdFilter.HEADER)!! shouldHaveLength 36
    }

    @Test
    fun `truncates an oversized caller supplied id`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        request.addHeader(RequestIdFilter.HEADER, "x".repeat(500))
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        response.getHeader(RequestIdFilter.HEADER)!! shouldHaveLength 128
    }

    @Test
    fun `puts the request id in the MDC during the request and clears it after`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        request.addHeader(RequestIdFilter.HEADER, "mdc-check")
        var seenInsideChain: String? = null

        // Threads are pooled, so an id left behind would leak into the next
        // request served by this thread. Assert it is gone afterwards.
        val chain = MockFilterChain(
            object : jakarta.servlet.http.HttpServlet() {
                override fun service(req: HttpServletRequest, res: HttpServletResponse) {
                    seenInsideChain = MDC.get(RequestIdFilter.MDC_KEY)
                }
            },
        )

        filter.doFilter(request, MockHttpServletResponse(), chain)

        seenInsideChain shouldBe "mdc-check"
        MDC.get(RequestIdFilter.MDC_KEY) shouldBe null
    }
}
