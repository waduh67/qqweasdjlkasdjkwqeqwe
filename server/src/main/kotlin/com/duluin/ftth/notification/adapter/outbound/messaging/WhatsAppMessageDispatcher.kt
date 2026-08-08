package com.duluin.ftth.notification.adapter.outbound.messaging

import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.WhatsAppGateway
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.net.URI

/**
 * Dispatcher WhatsApp: satu router yang mengeksekusi transport sesuai tipe
 * [WhatsAppGateway] yang sudah teresolusi tenant.
 *
 *  - [WhatsAppGateway.Log]        cukup catat ke log, dianggap terkirim (dev/uji).
 *  - [WhatsAppGateway.HttpGeneric] satu POST form ke endpoint tenant (Fonnte/Wablas/dsb);
 *                                  token dikirim di header `Authorization` (konvensi kedua
 *                                  gateway itu), nama field nomor & pesan mengikut setelan.
 *  - [WhatsAppGateway.MetaCloud]  POST JSON ke Graph API `/{phoneNumberId}/messages` dengan
 *                                  bearer token; template (bila diset) atau teks bebas.
 *  - [WhatsAppGateway.Qontak]     POST JSON ke `/v1/broadcasts/whatsapp/direct`; HANYA template
 *                                  — tak ada jalur teks bebas di API itu.
 *
 * [RestClient] dirakit lewat [WhatsAppHttp.restClient] alih-alih bean, agar tak bentrok dengan bean
 * `RestClient` GenieACS saat resolusi tipe (pola [com.duluin.ftth.bng.adapter.outbound.routeros.RouterOsRestAdapter]).
 * Endpoint berbeda per-tenant/per-kirim, jadi URI absolut diberikan per panggilan (tanpa baseUrl).
 * Kegagalan transport dipetakan ke [DeliveryStatus.FAILED] (layak dicoba ulang), bukan melempar,
 * agar satu nomor gagal tak menggagalkan seluruh batch broadcast.
 */
@Component
class WhatsAppMessageDispatcher internal constructor(
    private val restClient: RestClient,
) : MessageDispatcher {

    // Konstruktor yang dipakai Spring: rakit client sendiri (tanpa bean RestClient).
    constructor() : this(WhatsAppHttp.restClient())

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(
        gateway: WhatsAppGateway,
        phone: String,
        recipientName: String,
        message: String,
    ): DeliveryOutcome =
        when (gateway) {
            WhatsAppGateway.Log -> {
                log.info("[WA/LOG] → {} : {}", phone, message)
                DeliveryOutcome(DeliveryStatus.SENT, "dicatat ke log (dev)")
            }
            is WhatsAppGateway.HttpGeneric -> sendHttpGeneric(gateway, phone, message)
            is WhatsAppGateway.MetaCloud -> sendMetaCloud(gateway, phone, message)
            is WhatsAppGateway.Qontak -> sendQontak(gateway, phone, recipientName, message)
        }

    private fun sendHttpGeneric(gateway: WhatsAppGateway.HttpGeneric, phone: String, message: String): DeliveryOutcome {
        val form = LinkedMultiValueMap<String, String>().apply {
            add(gateway.phoneField, phone)
            add(gateway.messageField, message)
        }
        return runCatching {
            val spec = restClient.post()
                .uri(URI.create(gateway.endpointUrl))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            if (!gateway.token.isNullOrBlank()) spec.header(HttpHeaders.AUTHORIZATION, gateway.token)
            spec.body(form).retrieve().toBodilessEntity()
        }.fold(
            onSuccess = { DeliveryOutcome(DeliveryStatus.SENT, "Terkirim via gateway HTTP (${it.statusCode.value()})") },
            onFailure = { DeliveryOutcome(DeliveryStatus.FAILED, transportError("Gateway HTTP", it)) },
        )
    }

    private fun sendMetaCloud(gateway: WhatsAppGateway.MetaCloud, phone: String, message: String): DeliveryOutcome {
        // Meta menuntut MSISDN internasional tanpa '+'. Normalisasi ringan; nomor benar
        // tetap tanggung jawab operator (E.164 penuh di luar cakupan dispatcher).
        val to = phone.trim().removePrefix("+")
        val body = if (gateway.templateName != null) {
            mapOf(
                "messaging_product" to "whatsapp",
                "to" to to,
                "type" to "template",
                "template" to mapOf(
                    "name" to gateway.templateName,
                    "language" to mapOf("code" to gateway.templateLang),
                    // Konvensi: template ber-satu variabel body {{1}} = isi pesan dinamis.
                    "components" to listOf(
                        mapOf(
                            "type" to "body",
                            "parameters" to listOf(mapOf("type" to "text", "text" to message)),
                        ),
                    ),
                ),
            )
        } else {
            mapOf(
                "messaging_product" to "whatsapp",
                "to" to to,
                "type" to "text",
                "text" to mapOf("body" to message),
            )
        }
        return runCatching {
            restClient.post()
                .uri(URI.create("${MetaGraph.BASE}/${gateway.phoneNumberId}/messages"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${gateway.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity()
        }.fold(
            onSuccess = { DeliveryOutcome(DeliveryStatus.SENT, "Terkirim via Meta Cloud (${it.statusCode.value()})") },
            onFailure = { DeliveryOutcome(DeliveryStatus.FAILED, transportError("Meta Cloud", it)) },
        )
    }

    /**
     * Kirim lewat broadcast langsung Qontak. Dua hal yang membedakannya dari Meta:
     *
     *  1. **Tanpa template, tak ada kiriman.** `/broadcasts/whatsapp/direct` hanya menerima
     *     `message_template_id`; tak ada padanan `type: "text"`. Jadi pemicu yang belum
     *     dipetakan ke template berakhir [DeliveryStatus.SKIPPED] — bukan FAILED (tak ada yang
     *     rusak, tak ada gunanya dicoba ulang) dan bukan diam-diam jatuh ke teks biasa seperti Meta.
     *  2. `to_name` wajib, karena itu [recipientName] dibawa sampai ke sini.
     *
     * `parameters.body` memakai konvensi kita: satu variabel `{{1}}` berisi seluruh pesan.
     * `value` adalah NAMA variabel yang tampil di dasbor, `value_text` isinya yang sebenarnya.
     */
    private fun sendQontak(
        gateway: WhatsAppGateway.Qontak,
        phone: String,
        recipientName: String,
        message: String,
    ): DeliveryOutcome {
        val templateId = gateway.templateId
            ?: return DeliveryOutcome(
                DeliveryStatus.SKIPPED,
                "Mekari Qontak hanya bisa mengirim template — pemicu ini belum dipetakan ke template mana pun",
            )
        val to = phone.trim().removePrefix("+")
        val body = mapOf(
            "to_name" to recipientName.trim().ifEmpty { to },
            "to_number" to to,
            "message_template_id" to templateId,
            "channel_integration_id" to gateway.channelIntegrationId,
            "language" to mapOf("code" to gateway.templateLang),
            "parameters" to mapOf(
                "body" to listOf(
                    mapOf("key" to "1", "value" to "pesan", "value_text" to message),
                ),
            ),
        )
        return runCatching {
            restClient.post()
                .uri(URI.create("${QontakApi.BASE}/v1/broadcasts/whatsapp/direct"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${gateway.accessToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity()
        }.fold(
            onSuccess = {
                DeliveryOutcome(DeliveryStatus.SENT, "Terkirim via ${QontakApi.LABEL} (${it.statusCode.value()})")
            },
            onFailure = { DeliveryOutcome(DeliveryStatus.FAILED, transportError(QontakApi.LABEL, it)) },
        )
    }

    /** Rapikan error transport jadi keterangan ringkas (muat kolom detail 300 char). */
    private fun transportError(label: String, e: Throwable): String = WhatsAppHttp.transportError(label, e)
}
