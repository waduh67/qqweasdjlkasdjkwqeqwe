package com.duluin.ftth.notification.adapter.outbound.messaging

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration

/**
 * Hal-hal bersama untuk semua panggilan HTTP ke penyedia WhatsApp (Meta Graph maupun Qontak
 * Open API): perakitan client dan perapian pesan error. Dikumpulkan di satu tempat agar
 * timeout dan bentuk pesan tak bercabang antar adapter.
 *
 * [restClient] dibangun sendiri, bukan bean, agar tak bentrok dengan bean `RestClient`
 * GenieACS saat resolusi tipe (pola yang sama dipakai RouterOsRestAdapter). URI absolut
 * diberikan per panggilan karena host/path berbeda per tenant.
 */
internal object WhatsAppHttp {
    private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
    private val READ_TIMEOUT = Duration.ofSeconds(15)

    fun restClient(): RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                setReadTimeout(READ_TIMEOUT)
            },
        )
        .build()

    /** Rapikan error transport jadi keterangan ringkas (muat kolom detail 300 char). */
    fun transportError(label: String, e: Throwable): String = when (e) {
        is RestClientResponseException ->
            "$label menolak (${e.statusCode.value()}): ${e.responseBodyAsString.take(180)}"
        else -> "$label tak terjangkau: ${e.message?.take(180)}"
    }
}

/** Alamat & label Graph API Meta. */
internal object MetaGraph {
    const val BASE = "https://graph.facebook.com/v21.0"
    const val LABEL = "Meta Cloud"
}

/**
 * Alamat & label Open API Mekari Qontak. Host `service-chat.qontak.com` dengan awalan
 * `/api/open` adalah endpoint Open API v1.0.3 yang memakai bearer token dasbor — berbeda dari
 * gerbang `api.mekari.com` yang menuntut tanda tangan HMAC dan tak dipakai di sini.
 */
internal object QontakApi {
    const val BASE = "https://service-chat.qontak.com/api/open"
    const val LABEL = "Mekari Qontak"
}
