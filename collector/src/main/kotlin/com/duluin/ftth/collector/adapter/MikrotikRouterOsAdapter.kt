package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.RadiusSessionReading
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Adapter BRAS nyata untuk MikroTik RouterOS lewat REST API v7.
 *
 * RouterOS ≥ 7 memaparkan seluruh menu CLI sebagai REST di bawah `/rest` (aktifkan
 * service `www` untuk HTTP atau `www-ssl` untuk HTTPS). Adapter ini memakainya untuk:
 *  - **membaca sesi**: `GET /rest/ppp/active` (satu entri per sesi PPPoE hidup), dan
 *    `GET /rest/interface` untuk penghitung byte kumulatif interface dinamis
 *    `<pppoe-username>` — dari situ server menghitung laju Mbps antar-poll;
 *  - **DISCONNECT**: menghapus entri `/ppp/active` (memutus sesi) — idempoten, sesi
 *    yang sudah tak ada dianggap selesai;
 *  - **CoA**: mengubah `max-limit` pada simple queue dinamis `<pppoe-username>` yang
 *    dibuat RouterOS saat rate-limit di-set pada profil PPP — kecepatan berubah tanpa
 *    memutus sesi.
 *
 * Sertifikat www-ssl RouterOS biasanya self-signed; klien bawaan memercayai rantai apa
 * pun (perangkat ada di segmen manajemen ISP yang tepercaya, seperti community SNMP OLT
 * yang juga dikirim polos). Verifikasi hostname tetap berlaku pada JDK HttpClient — untuk
 * lab dengan cert self-signed, pakai HTTP (`www`) atau cert yang cocok dengan alamatnya.
 */
class MikrotikRouterOsAdapter(
    private val http: HttpClient = trustAllClient(),
    private val requestTimeout: Duration = Duration.ofSeconds(15),
) : BngAdapter {

    override val vendor: String = VENDOR

    private val log = LoggerFactory.getLogger(javaClass)

    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    override fun pollSessions(target: NasTarget): List<RadiusSessionReading> {
        val active = getList(target, "/ppp/active", Array<ActiveEntry>::class.java)
        // Octet kumulatif ada di interface dinamis, bukan di /ppp/active — dibaca terpisah
        // dan best-effort: kegagalannya hanya menghilangkan laju, bukan seluruh sesi.
        val bytesByIface = interfaceBytes(target)
        return active.mapNotNull { entry ->
            val username = entry.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val bytes = bytesByIface["<pppoe-$username>"]
            RadiusSessionReading(
                username = username,
                online = true,
                framedIp = entry.address,
                nasIp = target.host,
                sessionId = entry.sessionId ?: entry.id,
                callingStationId = entry.callerId,
                uptimeSeconds = entry.uptime?.let(::parseRouterOsDuration),
                // Interface dinamis dilihat dari sisi BRAS: rx = unggah pelanggan (masuk
                // BRAS) = inOctets; tx = unduh (keluar BRAS) = outOctets.
                inOctets = bytes?.rx,
                outOctets = bytes?.tx,
            )
        }
    }

    override fun execute(target: NasTarget, action: BngActionCommand) {
        when (action.kind) {
            BngActionKind.DISCONNECT -> disconnect(target, action.username)
            BngActionKind.COA -> changeRate(target, action.username, action.downMbps, action.upMbps)
            // Provisioning (kredensial + grup paket) berpusat di FreeRADIUS, bukan di
            // router: RouterOS cukup jadi RADIUS client. Melempar jujur agar operator
            // mengarahkan akun ke NAS ber-adapter FreeRADIUS, bukan diam-diam tak berefek.
            BngActionKind.PROVISION, BngActionKind.DEPROVISION, BngActionKind.SYNC_GROUP ->
                throw IllegalStateException(
                    "RouterOS ${target.name}: ${action.kind} lewat RADIUS-pusat — arahkan akun ke NAS ber-adapter FreeRADIUS",
                )
        }
    }

    /** Memutus sesi dengan menghapus entri `/ppp/active`; idempoten bila sesi sudah tak ada. */
    private fun disconnect(target: NasTarget, username: String) {
        val id = findActiveId(target, username)
        if (id == null) {
            log.info("RouterOS {}: sesi {} sudah tak aktif — DISCONNECT dianggap selesai", target.name, username)
            return
        }
        send(target, "DELETE", "/ppp/active/$id", null)
        log.info("RouterOS {}: memutus sesi {} ({})", target.name, username, id)
    }

    /**
     * Mengubah kecepatan sesi hidup lewat `max-limit` simple queue dinamis
     * `<pppoe-username>`. Melempar bila antreannya tak ada (rate-limit belum diatur di
     * profil PPP) — jadi ACK gagal membawa sebab yang jelas, bukan diam-diam tak berefek.
     */
    private fun changeRate(target: NasTarget, username: String, downMbps: Int?, upMbps: Int?) {
        require(downMbps != null && upMbps != null) { "CoA butuh downMbps & upMbps" }
        val queueId = findQueueId(target, username)
            ?: throw IllegalStateException(
                "CoA gagal: simple queue '<pppoe-$username>' tak ditemukan di ${target.name} " +
                    "(atur rate-limit pada profil PPP agar RouterOS membuat antreannya)",
            )
        // Simple queue max-limit = target-upload/target-download.
        val body = mapper.writeValueAsString(mapOf("max-limit" to "${upMbps}M/${downMbps}M"))
        send(target, "PATCH", "/queue/simple/$queueId", body)
        log.info("RouterOS {}: CoA {} → {}/{} Mbps", target.name, username, downMbps, upMbps)
    }

    private fun findActiveId(target: NasTarget, username: String): String? =
        getList(target, "/ppp/active", Array<ActiveEntry>::class.java)
            .firstOrNull { it.name == username }?.id

    private fun findQueueId(target: NasTarget, username: String): String? =
        getList(target, "/queue/simple", Array<QueueEntry>::class.java)
            .firstOrNull { it.name == "<pppoe-$username>" }?.id

    private fun interfaceBytes(target: NasTarget): Map<String, IfaceBytes> =
        runCatching {
            getList(target, "/interface", Array<IfaceEntry>::class.java)
                .filter { !it.name.isNullOrBlank() }
                .associate { it.name!! to IfaceBytes(it.rxByte?.toLongOrNull(), it.txByte?.toLongOrNull()) }
        }.getOrElse {
            log.debug("RouterOS {}: gagal baca /interface untuk octet: {}", target.name, it.message)
            emptyMap()
        }

    private fun <T> getList(target: NasTarget, path: String, arrayType: Class<Array<T>>): List<T> =
        send(target, "GET", path, null).let { body ->
            if (body.isBlank()) emptyList() else mapper.readValue(body, arrayType).toList()
        }

    private fun send(target: NasTarget, method: String, path: String, body: String?): String {
        val user = target.apiUsername?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("BRAS ${target.name}: user REST RouterOS belum diisi")
        val host = target.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("BRAS ${target.name}: alamat manajemen belum diisi")
        val scheme = if (target.apiUseTls) "https" else "http"
        val port = target.apiPort ?: if (target.apiUseTls) 443 else 80
        val auth = Base64.getEncoder()
            .encodeToString("$user:${target.apiSecret ?: ""}".toByteArray(StandardCharsets.UTF_8))
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)

        val request = HttpRequest.newBuilder(URI.create("$scheme://$host:$port/rest$path"))
            .timeout(requestTimeout)
            .header("Authorization", "Basic $auth")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .method(method, publisher)
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "RouterOS ${target.name} $method $path → HTTP ${response.statusCode()}: ${response.body().take(300)}",
            )
        }
        return response.body()
    }

    // RouterOS REST mengembalikan nilai apa adanya sebagai string JSON; nama field
    // ber-titik/tanda-hubung dipetakan eksplisit. Field tak dikenal diabaikan.
    private data class ActiveEntry(
        @param:JsonProperty(".id") val id: String? = null,
        val name: String? = null,
        val address: String? = null,
        @param:JsonProperty("caller-id") val callerId: String? = null,
        @param:JsonProperty("session-id") val sessionId: String? = null,
        val uptime: String? = null,
    )

    private data class QueueEntry(
        @param:JsonProperty(".id") val id: String? = null,
        val name: String? = null,
    )

    private data class IfaceEntry(
        val name: String? = null,
        @param:JsonProperty("rx-byte") val rxByte: String? = null,
        @param:JsonProperty("tx-byte") val txByte: String? = null,
    )

    private data class IfaceBytes(val rx: Long?, val tx: Long?)

    companion object {
        const val VENDOR = "MIKROTIK"

        /**
         * Mengurai durasi gaya RouterOS ("6w6d23h59m58s", "1d2h", "45s", "990ms") menjadi
         * detik. Satuan sub-detik (ms/us) diabaikan (dibulatkan ke bawah), sisanya
         * dijumlahkan. Mengembalikan null bila tak ada satu angka pun.
         */
        internal fun parseRouterOsDuration(text: String): Long? {
            if (text.isBlank()) return null
            var total = 0L
            var num = 0L
            var seen = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.isDigit()) {
                    num = num * 10 + (c - '0')
                    seen = true
                    i++
                    continue
                }
                val unit = when {
                    c == 'w' -> 604_800L
                    c == 'd' -> 86_400L
                    c == 'h' -> 3_600L
                    // "ms"/"us" (sub-detik) — konsumsi 's', tak menambah apa-apa.
                    (c == 'm' || c == 'u') && i + 1 < text.length && text[i + 1] == 's' -> { i++; 0L }
                    c == 'm' -> 60L
                    c == 's' -> 1L
                    else -> return if (seen) total else null
                }
                total += num * unit
                num = 0
                i++
            }
            return if (seen) total else null
        }

        /**
         * Klien yang memercayai sertifikat TLS apa pun — www-ssl RouterOS lazim
         * self-signed dan perangkatnya di segmen manajemen ISP yang tepercaya. Hanya
         * dipakai adapter ini; kanal collector→server tetap memakai klien default berverifikasi.
         */
        private fun trustAllClient(): HttpClient {
            val trustAll = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                },
            )
            val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(ctx)
                .build()
        }
    }
}
