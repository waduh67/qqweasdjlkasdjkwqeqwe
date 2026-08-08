package com.duluin.ftth.notification.adapter.outbound.messaging

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.notification.application.port.outbound.QontakChannel
import com.duluin.ftth.notification.application.port.outbound.QontakChannelDirectory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Daftar kanal WhatsApp milik akun Qontak: `GET /v1/integrations?target_channel=wa`.
 *
 * Dipakai untuk mengisi dropdown pilihan kanal di kartu Gateway — `channel_integration_id`
 * adalah UUID yang mustahil diketik benar dari ingatan, jadi operator memilih dari daftar
 * alih-alih menyalinnya dari dasbor.
 *
 * Kanal nonaktif disaring: memilihnya hanya akan membuat pengiriman gagal belakangan dengan
 * pesan yang jauh dari sebabnya.
 */
@Component
class QontakChannelDirectoryAdapter internal constructor(
    private val objectMapper: ObjectMapper,
    private val restClient: RestClient,
) : QontakChannelDirectory {

    /** Lihat catatan konstruktor kembar di [MetaTemplateCatalogAdapter] — alasannya sama. */
    @Autowired
    constructor(objectMapper: ObjectMapper) : this(objectMapper, WhatsAppHttp.restClient())

    override fun list(accessToken: String): List<QontakChannel> {
        val url = "${QontakApi.BASE}/v1/integrations?target_channel=wa&limit=$LIMIT"
        val raw = try {
            restClient.get()
                .uri(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .retrieve()
                .body(String::class.java)
        } catch (e: Exception) {
            throw ConflictException(WhatsAppHttp.transportError(QontakApi.LABEL, e))
        }
        val node = raw?.takeIf { it.isNotBlank() }?.let(objectMapper::readTree)
            ?: throw ConflictException("${QontakApi.LABEL} tak menjawab permintaan daftar channel")
        return node.get("data")?.takeIf { it.isArray }?.mapNotNull(::parse).orEmpty()
    }

    private fun parse(el: JsonNode): QontakChannel? {
        val id = el.get("id")?.takeIf { !it.isNull }?.asString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Hanya `false` eksplisit yang menyingkirkan kanal; field yang absen dianggap aktif
        // supaya perubahan bentuk respons tak diam-diam mengosongkan seluruh dropdown.
        val active = el.get("is_active")?.takeIf { !it.isNull }?.asBoolean() ?: true
        if (!active) return null
        val name = el.at("/settings/account_name").takeIf { !it.isNull && !it.isMissingNode }
            ?.asString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: el.get("name")?.takeIf { !it.isNull }?.asString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: id
        return QontakChannel(id = id, name = name)
    }

    private companion object {
        const val LIMIT = 50
    }
}
