package com.duluin.ftth.inventory

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WarehouseResponseLossProbe {
    class Gate(val path: String) {
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        @Volatile var status = 0
    }
    val pending = AtomicReference<Gate?>()
    fun arm(path: String) = Gate(path).also { check(pending.compareAndSet(null, it)) }
    fun clear(gate: Gate) { check(pending.compareAndSet(gate, null)) }
}

@TestConfiguration(proxyBeanMethods = false)
class WarehouseResponseLossConfiguration {
    @Bean fun responseLossProbe() = WarehouseResponseLossProbe()
    @Bean fun withholdCommittedResponse(probe: WarehouseResponseLossProbe) = object : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            val gate = probe.pending.get()?.takeIf { it.path == request.requestURI }
            if (gate == null) { chain.doFilter(request, response); return }
            val held = ContentCachingResponseWrapper(response)
            try {
                chain.doFilter(request, held)
                gate.status = held.status
                gate.committed.countDown()
                check(gate.release.await(30, TimeUnit.SECONDS))
                held.copyBodyToResponse()
            } finally { gate.finished.countDown() }
        }
    }
}
