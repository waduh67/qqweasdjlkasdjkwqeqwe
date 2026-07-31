package com.duluin.ftth.bng.adapter.outbound.routeros

import com.duluin.ftth.bng.application.port.outbound.PppSecret
import com.duluin.ftth.bng.application.port.outbound.RouterOsPort
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.NasVendor
import com.duluin.ftth.common.domain.error.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration

/**
 * Adapter REST RouterOS (vendor MIKROTIK) untuk menarik `/ppp/secret`. [RestClient] dibangun
 * PER-BRAS (host/port/kredensial berbeda tiap NAS), bukan bean tunggal seperti GenieACS.
 * Basic auth memakai kredensial kontrol NAS (apiUsername/apiSecret) — sama yang dipakai CoA
 * REST — dan [Nas] yang masuk sudah ter-dekripsi di batas persistence, jadi [apiSecret]
 * plaintext di sini.
 *
 * TLS: RouterOS default ber-sertifikat self-signed. Jalur manajemen kita lewat overlay VPN
 * (sudah terenkripsi), jadi http (`apiUseTls=false`) adalah pola yang dianjurkan; https hanya
 * bila router memasang sertifikat tepercaya (validasi TLS TIDAK dilonggarkan — no trust-all).
 * Kegagalan koneksi/otentikasi dibungkus [ConflictException] agar controller membalas 409
 * dengan pesan jelas alih-alih 500 mentah.
 */
@Component
class RouterOsRestAdapter : RouterOsPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun fetchPppSecrets(nas: Nas): List<PppSecret> {
        if (nas.vendor != NasVendor.MIKROTIK) {
            throw ConflictException("Impor PPPoE lewat REST hanya untuk BRAS MikroTik (RouterOS)")
        }
        val host = nas.address?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ConflictException("BRAS belum punya alamat manajemen — isi dulu di form BRAS")
        val user = nas.apiUsername?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ConflictException("BRAS belum punya user kontrol REST — isi dulu di form BRAS")
        val secret = nas.apiSecret
            ?: throw ConflictException("BRAS belum punya password kontrol REST — isi dulu di form BRAS")

        val rows = try {
            client(host, nas.apiPort, nas.apiUseTls, user, secret)
                .get().uri("/rest/ppp/secret")
                .retrieve()
                .body(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
                ?: emptyList()
        } catch (e: RestClientException) {
            log.warn("Gagal menarik /ppp/secret dari BRAS {} ({}): {}", nas.name, host, e.message)
            throw ConflictException(
                "Gagal menghubungi RouterOS di $host — cek alamat, port, kredensial kontrol, dan rute manajemen",
            )
        }
        return rows.map { it.toPppSecret() }
    }

    private fun client(host: String, port: Int?, useTls: Boolean, user: String, secret: String): RestClient {
        val scheme = if (useTls) "https" else "http"
        val effectivePort = port ?: if (useTls) 443 else 80
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(CONNECT_TIMEOUT)
            setReadTimeout(READ_TIMEOUT)
        }
        return RestClient.builder()
            .baseUrl("$scheme://$host:$effectivePort")
            .requestFactory(factory)
            .defaultHeaders { it.setBasicAuth(user, secret) }
            .build()
    }

    /** RouterOS mengembalikan boolean sebagai string `"true"`/`"false"`; kosong → apa adanya null. */
    private fun Map<String, Any?>.toPppSecret(): PppSecret = PppSecret(
        name = str("name").orEmpty(),
        password = str("password"),
        profile = str("profile"),
        service = str("service"),
        comment = str("comment"),
        disabled = str("disabled")?.equals("true", ignoreCase = true) ?: false,
    )

    private fun Map<String, Any?>.str(key: String): String? =
        (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
