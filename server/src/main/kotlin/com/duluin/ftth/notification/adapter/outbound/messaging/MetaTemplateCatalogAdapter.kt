package com.duluin.ftth.notification.adapter.outbound.messaging

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.notification.application.port.outbound.RemoteTemplate
import com.duluin.ftth.notification.application.port.outbound.TemplateDraft
import com.duluin.ftth.notification.application.port.outbound.WhatsAppTemplateCatalog
import com.duluin.ftth.notification.domain.model.TemplateApi
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateStatus
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Katalog template Meta lewat Graph API — CRUD penuh, bukan cuma baca:
 *
 *  - daftar `GET  /{waba-id}/message_templates?fields=…`
 *  - buat   `POST /{waba-id}/message_templates`
 *  - ubah   `POST /{template-id}` (hanya `components`/`category`)
 *  - hapus  `DELETE /{waba-id}/message_templates?name=…&hsm_id=…`
 *
 * Dua aturan Meta yang ditegakkan di sini, bukan dibiarkan jadi 400 mentah:
 *
 *  1. Nama & bahasa TAK bisa diubah, dan hanya template APPROVED/REJECTED yang boleh
 *     disunting — makanya [edit] tak menerima nama dan Meta sendiri yang memvonis statusnya.
 *  2. `DELETE` tanpa `hsm_id` menghapus SEMUA bahasa bernama sama. Kita selalu mengirim
 *     `hsm_id`, jadi menghapus template `id` tak pernah ikut menghapus versi `en_US`-nya.
 *
 * Pembacaan bersifat TOLERAN: nilai `status`/`category` yang tak dikenal dipetakan ke
 * [TemplateStatus.UNKNOWN]/[TemplateCategory.UTILITY] dan baris tanpa `name` dilewati, alih-alih
 * menggagalkan seluruh sync. Meta menambah nilai enum baru dari waktu ke waktu (mis. kategori
 * lawas TRANSACTIONAL→UTILITY), dan satu template aneh tak boleh membuat operator kehilangan
 * seluruh daftarnya.
 *
 * Paging Graph API diikuti lewat `paging.next` (URL lengkap ber-cursor), dibatasi
 * [MAX_PAGES] halaman agar respons rusak tak berujung loop tak-hingga.
 */
@Component
class MetaTemplateCatalogAdapter internal constructor(
    private val objectMapper: ObjectMapper,
    private val restClient: RestClient,
) : WhatsAppTemplateCatalog {

    /**
     * Konstruktor yang dipakai Spring: rakit client sendiri, JANGAN suntik bean [RestClient]
     * (ada bean RestClient GenieACS yang akan tersambar). `@Autowired` wajib di sini — kelas
     * ini punya dua konstruktor dan tak punya yang tanpa-argumen, jadi tanpa penanda Spring
     * gagal memilih dan bean tak terbentuk saat boot.
     */
    @Autowired
    constructor(objectMapper: ObjectMapper) : this(objectMapper, WhatsAppHttp.restClient())

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider = WhatsAppProvider.META_CLOUD
    override val label = MetaGraph.LABEL
    override val canEdit = true
    override val canDeleteRemotely = true

    override fun list(api: TemplateApi): List<RemoteTemplate> {
        val meta = meta(api)
        val templates = mutableListOf<RemoteTemplate>()
        var url: String? = "${MetaGraph.BASE}/${meta.wabaId}/message_templates?fields=$FIELDS&limit=$PAGE_SIZE"
        var page = 0
        while (url != null && page < MAX_PAGES) {
            val node = get(url, meta.accessToken)
            node.get("data")?.takeIf { it.isArray }?.forEach { el ->
                parse(el)?.let(templates::add)
            }
            url = node.at("/paging/next").takeIf { !it.isNull && !it.isMissingNode }
                ?.asString()?.takeIf { it.isNotBlank() }
            page++
        }
        if (url != null) log.warn("Daftar template Meta terpotong di halaman {} (batas aman)", MAX_PAGES)
        return templates
    }

    override fun create(api: TemplateApi, draft: TemplateDraft): RemoteTemplate {
        val meta = meta(api)
        val body = mapOf(
            "name" to draft.name,
            "language" to draft.language,
            "category" to draft.category.name,
            "components" to listOf(bodyComponent(draft.bodyText)),
        )
        val node = post("${MetaGraph.BASE}/${meta.wabaId}/message_templates", meta.accessToken, body)
        // Respons buat hanya berisi {id, status, category} — nama/bahasa/isi kita sudah tahu.
        return RemoteTemplate(
            remoteId = node.get("id")?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() },
            name = draft.name,
            language = draft.language,
            category = category(node.get("category")?.takeIf { !it.isNull }?.asString() ?: draft.category.name),
            status = status(node.get("status")?.takeIf { !it.isNull }?.asString() ?: "PENDING"),
            bodyText = draft.bodyText,
        )
    }

    override fun edit(api: TemplateApi, remoteId: String, draft: TemplateDraft): RemoteTemplate {
        val meta = meta(api)
        // Meta hanya mengizinkan menyunting template APPROVED/REJECTED. Statusnya ditanyakan
        // lebih dulu agar operator membaca kalimat yang berguna, bukan 400 mentah dari Graph —
        // dan status yang dipakai adalah milik Meta saat ini, bukan cermin lokal yang bisa basi.
        val current = fetchOne(meta, remoteId)
        if (current != null && current.status !in EDITABLE) {
            throw ConflictException(
                "Template berstatus ${current.status} tak bisa disunting di Meta — hanya yang " +
                    "sudah disetujui atau ditolak yang boleh diubah. Tunggu peninjauan selesai " +
                    "atau hapus lalu buat baru.",
            )
        }
        // Endpoint sunting beralamat ke template, bukan ke WABA; nama & bahasa tak disertakan
        // karena Meta menolak permintaan yang mencoba mengubahnya.
        val body = mapOf(
            "category" to draft.category.name,
            "components" to listOf(bodyComponent(draft.bodyText)),
        )
        post("${MetaGraph.BASE}/$remoteId", meta.accessToken, body)
        // Respons sunting hanya `{"success": true}` — status barunya (kembali PENDING karena
        // suntingan masuk antrean peninjauan) baru terlihat saat menarik ulang template ini.
        return fetchOne(meta, remoteId) ?: RemoteTemplate(
            remoteId = remoteId,
            name = draft.name,
            language = draft.language,
            category = draft.category,
            status = TemplateStatus.PENDING,
            bodyText = draft.bodyText,
        )
    }

    override fun delete(api: TemplateApi, remoteId: String, name: String) {
        val meta = meta(api)
        // hsm_id WAJIB: tanpa itu Meta menghapus semua bahasa yang bernama sama.
        val url = "${MetaGraph.BASE}/${meta.wabaId}/message_templates?name=$name&hsm_id=$remoteId"
        try {
            restClient.delete()
                .uri(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${meta.accessToken}")
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            throw ConflictException(WhatsAppHttp.transportError(MetaGraph.LABEL, e))
        }
    }

    /** Ambil satu template lewat id-nya, untuk membaca status terbaru sesudah menyunting. */
    private fun fetchOne(meta: TemplateApi.Meta, remoteId: String): RemoteTemplate? =
        runCatching { parse(get("${MetaGraph.BASE}/$remoteId?fields=$FIELDS", meta.accessToken)) }.getOrNull()

    /** Komponen BODY beserta contoh isian — Meta menolak template ber-`{{n}}` tanpa `example`. */
    private fun bodyComponent(bodyText: String) = mapOf(
        "type" to "BODY",
        "text" to bodyText,
        // body_text = array per-variabel di dalam array per-contoh, jadi bersarang dua.
        "example" to mapOf("body_text" to listOf(listOf(EXAMPLE_VALUE))),
    )

    private fun meta(api: TemplateApi): TemplateApi.Meta = api as? TemplateApi.Meta
        ?: throw ConflictException("Kredensial Meta Cloud tak cocok dengan penyedia yang dipilih")

    private fun get(url: String, accessToken: String): JsonNode = read(url) {
        restClient.get()
            .uri(URI.create(url))
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .retrieve()
            .body(String::class.java)
    }

    private fun post(url: String, accessToken: String, body: Map<String, Any>): JsonNode = read(url) {
        restClient.post()
            .uri(URI.create(url))
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String::class.java)
    }

    private fun read(url: String, call: () -> String?): JsonNode {
        val raw = try {
            call()
        } catch (e: Exception) {
            throw ConflictException(WhatsAppHttp.transportError(MetaGraph.LABEL, e))
        }
        return raw?.takeIf { it.isNotBlank() }?.let(objectMapper::readTree)
            ?: throw ConflictException("Meta Cloud tak menjawab permintaan $url")
    }

    /** Satu elemen `data[]` → [RemoteTemplate]; null bila tak punya nama (tak bisa dipakai). */
    private fun parse(el: JsonNode): RemoteTemplate? {
        val name = el.get("name")?.takeIf { !it.isNull }?.asString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return RemoteTemplate(
            remoteId = el.get("id")?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() },
            name = name,
            language = el.get("language")?.takeIf { !it.isNull }?.asString()?.trim().orEmpty(),
            category = category(el.get("category")?.takeIf { !it.isNull }?.asString()),
            status = status(el.get("status")?.takeIf { !it.isNull }?.asString()),
            bodyText = bodyText(el.get("components")),
        )
    }

    /** Teks komponen `BODY` — bagian yang menampung variabel `{{n}}` yang kita isi saat kirim. */
    private fun bodyText(components: JsonNode?): String? {
        if (components == null || !components.isArray) return null
        return components.firstOrNull { it.get("type")?.asString()?.equals("BODY", ignoreCase = true) == true }
            ?.get("text")?.takeIf { !it.isNull }?.asString()
    }

    private companion object {
        const val FIELDS = "id,name,language,status,category,components"
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 5

        /** Status yang boleh disunting menurut Meta; sisanya ditolak sebelum panggilan dikirim. */
        val EDITABLE = setOf(TemplateStatus.APPROVED, TemplateStatus.REJECTED)

        /** Contoh isian `{{1}}` yang dilihat peninjau Meta; tak pernah terkirim ke pelanggan. */
        const val EXAMPLE_VALUE = "Tagihan Agustus 2025 sebesar Rp350.000 jatuh tempo 10 Agustus"
    }
}

/**
 * Kategori tak dikenal dianggap UTILITY: itu kategori transaksional yang relevan di sini, dan
 * salah-tebak paling banter memasukkan satu template ekstra ke katalog (bukan menyembunyikan
 * yang dibutuhkan). Kategori lawas `TRANSACTIONAL` = UTILITY sekarang.
 *
 * Dipakai bersama oleh adapter Meta dan Qontak: respons Qontak yang sebenarnya memakai
 * kosakata Meta yang sama, jadi menyalin peta ini ke sana hanya akan membuat keduanya melenceng.
 */
internal fun category(value: String?): TemplateCategory = when (value?.uppercase()?.trim()) {
    "MARKETING" -> TemplateCategory.MARKETING
    "AUTHENTICATION", "OTP" -> TemplateCategory.AUTHENTICATION
    else -> TemplateCategory.UTILITY
}

/**
 * Meta mendokumentasikan sepuluh status; domain kita hanya menyimpan enam (lihat CHECK di
 * V76). Empat sisanya dipetakan ke padanan terdekat menurut *bisa-tidaknya dikirim*, BUKAN
 * ke UNKNOWN — di UI, UNKNOWN berarti "belum pernah disinkron", jadi memakainya untuk baris
 * yang barusan ditarik dari penyedia justru menyesatkan operator.
 *
 * Nilai di luar daftar (Meta menambah enum baru dari waktu ke waktu) tetap jatuh ke UNKNOWN.
 */
internal fun status(value: String?): TemplateStatus = when (value?.uppercase()?.trim()) {
    "APPROVED" -> TemplateStatus.APPROVED
    "PENDING" -> TemplateStatus.PENDING
    // IN_APPEAL selalu menyusul penolakan: template tetap tak bisa dipakai selama banding.
    "REJECTED", "IN_APPEAL" -> TemplateStatus.REJECTED
    // Jeda sementara karena mutu/kuota — bisa pulih sendiri, beda dari dinonaktifkan permanen.
    "PAUSED", "LIMIT_EXCEEDED" -> TemplateStatus.PAUSED
    // Sudah tak dapat dikirim dan tak akan pulih tanpa tindakan di dasbor penyedia.
    "DISABLED", "ARCHIVED", "DELETED", "PENDING_DELETION" -> TemplateStatus.DISABLED
    else -> TemplateStatus.UNKNOWN
}
