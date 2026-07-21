package com.duluin.ftth.monitoring.adapter.inbound.security

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.CollectorProtocol
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Mengautentikasi collector lewat header API key dan memasang tenant context.
 *
 * Urutannya penting dan sengaja dibuat begini:
 *
 * 1. Kunci dicari lewat hash pada tabel `collector` yang TIDAK ber-RLS — pada
 *    titik ini tenant belum diketahui, jadi kebijakan RLS justru akan memblokir
 *    pencariannya sendiri.
 * 2. Tenant DIAMBIL dari baris collector, lalu dipasang ke [TenantContext]
 *    SEBELUM controller (dan transaksinya) berjalan. Kalau dipasang belakangan,
 *    sesi Hibernate sudah telanjur terbuka dengan tenant sentinel — kegagalan
 *    senyap yang sama seperti jebakan `open-in-view`.
 * 3. Context selalu dibersihkan di `finally`, karena thread dipakai ulang oleh
 *    request berikutnya yang bisa jadi milik tenant lain.
 *
 * SENGAJA BUKAN `@Component`. Spring Boot mendaftarkan setiap bean bertipe
 * `Filter` ke rantai servlet global, sehingga filter ini akan berjalan untuk
 * SELURUH request — termasuk `/api/auth/login`, yang lalu ditolak karena tidak
 * membawa header API key. Instansiasinya dilakukan `CollectorSecurityConfig`
 * agar ia hanya hidup di dalam rantai keamanan collector.
 */
class CollectorApiKeyFilter(
    private val authenticator: CollectorAuthenticator,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val apiKey = request.getHeader(CollectorProtocol.API_KEY_HEADER)
        if (apiKey.isNullOrBlank()) {
            reject(response, HttpStatus.UNAUTHORIZED, "Header ${CollectorProtocol.API_KEY_HEADER} wajib diisi")
            return
        }

        val declaredVersion = request.getHeader(CollectorProtocol.PROTOCOL_VERSION_HEADER)?.toIntOrNull()
        if (declaredVersion != null && declaredVersion != CollectorProtocol.PROTOCOL_VERSION) {
            // Agent versi lain bisa menafsirkan data secara berbeda; menolaknya
            // lebih baik daripada menyimpan metrik yang salah arti.
            reject(
                response,
                HttpStatus.UPGRADE_REQUIRED,
                "Versi protokol $declaredVersion tidak didukung (server: ${CollectorProtocol.PROTOCOL_VERSION})",
            )
            return
        }

        val collector = authenticator.authenticate(apiKey)
        if (collector == null) {
            // Pesannya sengaja seragam agar tidak bisa dipakai menebak kunci mana
            // yang pernah ada.
            log.warn("Autentikasi collector gagal dari {}", request.remoteAddr)
            reject(response, HttpStatus.UNAUTHORIZED, "API key collector tidak valid")
            return
        }
        if (!collector.canIngest()) {
            reject(response, HttpStatus.FORBIDDEN, "Collector dinonaktifkan")
            return
        }

        val principal = CollectorPrincipal(collector.id, collector.tenantId, collector.name)
        try {
            SecurityContextHolder.getContext().authentication = CollectorAuthenticationToken(principal)
            TenantContext.runAs(collector.tenantId) {
                filterChain.doFilter(request, response)
            }
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun reject(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = "application/json"
        response.writer.write("""{"detail":"$message"}""")
    }
}
