package com.duluin.ftth.notification.adapter.outbound.messaging

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.notification.application.port.outbound.RemoteTemplate
import com.duluin.ftth.notification.application.port.outbound.TemplateDraft
import com.duluin.ftth.notification.application.port.outbound.WhatsAppTemplateCatalog
import com.duluin.ftth.notification.domain.model.TemplateApi
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Katalog template Mekari Qontak lewat Open API. Kemampuannya lebih sempit daripada Meta, dan
 * itu diumumkan jujur alih-alih dipalsukan:
 *
 *  - daftar `GET  /v1/templates/whatsapp`
 *  - buat   `POST /v1/templates/whatsapp`
 *  - ubah   TIDAK ADA — dokumentasi Qontak: *"Once submitted for approval, a message template
 *           cannot be edited"*
 *  - hapus  TIDAK ADA — endpoint DELETE tak muncul di OpenAPI v1.0.3 maupun di daftar isi
 *           dokumentasi mereka
 *
 * Karena itu [canEdit] & [canDeleteRemotely] false, dan [edit]/[delete] melempar
 * [ConflictException] berpesan jelas kalau tetap dipanggil (service semestinya sudah menyaring
 * lebih dulu lewat kedua bendera itu).
 *
 * Perbedaan bentuk data dengan Meta yang ditangani di sini:
 *  - isi BODY datang sebagai STRING tunggal di field `body`, bukan array `components`;
 *  - `language` adalah kode telanjang (`"id"`), bukan objek;
 *  - saat MEMBUAT, isi justru harus dibungkus `attributes[].components[]` ala Meta — tak simetris
 *    dengan bentuk bacanya, jadi jangan disamakan.
 */
@Component
class QontakTemplateCatalogAdapter internal constructor(
    private val objectMapper: ObjectMapper,
    private val restClient: RestClient,
) : WhatsAppTemplateCatalog {

    /** Lihat catatan konstruktor kembar di [MetaTemplateCatalogAdapter] — alasannya sama. */
    @Autowired
    constructor(objectMapper: ObjectMapper) : this(objectMapper, WhatsAppHttp.restClient())

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider = WhatsAppProvider.QONTAK
    override val label = QontakApi.LABEL
    override val canEdit = false
    override val canDeleteRemotely = false

    /**
     * Paging Qontak memakai `offset` yang dijelaskan sebagai "seperti halaman", tanpa menyebut
     * basisnya 0 atau 1. Kita ambil halaman pertama tanpa `offset` (apa pun basisnya, itu halaman
     * awal), lalu `offset=2,3,…`, dan **dedup berdasarkan `id`** — sehingga tafsir mana pun yang
     * benar, hasilnya tetap utuh tanpa duplikat. Halaman kosong menghentikan iterasi.
     */
    override fun list(api: TemplateApi): List<RemoteTemplate> {
        val token = qontak(api).accessToken
        val byId = LinkedHashMap<String, RemoteTemplate>()
        val unidentified = mutableListOf<RemoteTemplate>()
        for (page in 1..MAX_PAGES) {
            val url = buildString {
                append("${QontakApi.BASE}/v1/templates/whatsapp?limit=$PAGE_SIZE")
                if (page > 1) append("&offset=$page")
            }
            val rows = get(url, token).get("data")?.takeIf { it.isArray }?.toList().orEmpty()
            if (rows.isEmpty()) break
            var fresh = 0
            rows.forEach { el ->
                val parsed = parse(el) ?: return@forEach
                if (parsed.remoteId == null) {
                    unidentified += parsed
                    fresh++
                } else if (byId.put(parsed.remoteId, parsed) == null) {
                    fresh++
                }
            }
            // Halaman yang seluruhnya berisi baris yang sudah kita punya = pengulangan halaman
            // pertama (tafsir offset yang keliru). Berhenti daripada menarik hal yang sama 5 kali.
            if (fresh == 0) break
        }
        if (unidentified.isNotEmpty()) {
            log.warn("{} template Qontak tanpa id — tak bisa dipakai mengirim", unidentified.size)
        }
        return byId.values + unidentified
    }

    /**
     * Ajukan template baru. Respons 201 Qontak tak didokumentasikan bentuknya, sementara
     * `message_template_id` WAJIB ada untuk mengirim — jadi sesudah pengajuan diterima kita
     * menariknya kembali lewat `?query={name}` untuk memastikan id-nya. Kalau pencarian itu
     * gagal pun template sudah terlanjur dibuat, jadi kita kembalikan hasil tanpa id dan
     * biarkan tombol "Tarik" menyusulkan id-nya nanti — bukan melempar error yang menyesatkan
     * seolah pengajuannya batal.
     */
    override fun create(api: TemplateApi, draft: TemplateDraft): RemoteTemplate {
        val token = qontak(api).accessToken
        val body = mapOf(
            "name" to draft.name,
            "category" to draft.category.name,
            "attributes" to listOf(
                mapOf(
                    "language" to draft.language,
                    "components" to listOf(
                        mapOf(
                            "type" to "BODY",
                            "text" to draft.bodyText,
                            // Beda dari Meta: contoh Qontak hanya SATU tingkat array.
                            "example" to mapOf("body_text" to listOf(EXAMPLE_VALUE)),
                        ),
                    ),
                ),
            ),
        )
        post("${QontakApi.BASE}/v1/templates/whatsapp", token, body)
        val created = findByName(token, draft.name, draft.language)
        return created ?: RemoteTemplate(
            remoteId = null,
            name = draft.name,
            language = draft.language,
            category = draft.category,
            status = TemplateStatus.PENDING,
            bodyText = draft.bodyText,
        )
    }

    override fun edit(api: TemplateApi, remoteId: String, draft: TemplateDraft): RemoteTemplate =
        throw ConflictException(
            "${QontakApi.LABEL} tak mengizinkan menyunting template yang sudah diajukan — " +
                "hapus template ini lalu buat yang baru.",
        )

    override fun delete(api: TemplateApi, remoteId: String, name: String): Unit =
        throw ConflictException(
            "${QontakApi.LABEL} tak menyediakan API hapus template — hapus lewat dasbor Qontak.",
        )

    /** Cari template yang baru dibuat untuk mengambil id-nya; null bila belum terlihat. */
    private fun findByName(token: String, name: String, language: String): RemoteTemplate? {
        val query = URLEncoder.encode(name, StandardCharsets.UTF_8)
        val url = "${QontakApi.BASE}/v1/templates/whatsapp?limit=$PAGE_SIZE&query=$query"
        val rows = runCatching { get(url, token).get("data")?.takeIf { it.isArray }?.toList() }
            .getOrNull().orEmpty()
        return rows.asSequence().mapNotNull(::parse)
            .firstOrNull { it.name == name && it.language.equals(language, ignoreCase = true) }
            ?: rows.asSequence().mapNotNull(::parse).firstOrNull { it.name == name }
    }

    private fun qontak(api: TemplateApi): TemplateApi.Qontak = api as? TemplateApi.Qontak
        ?: throw ConflictException("Kredensial ${QontakApi.LABEL} tak cocok dengan penyedia yang dipilih")

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
            throw ConflictException(WhatsAppHttp.transportError(QontakApi.LABEL, e))
        }
        return raw?.takeIf { it.isNotBlank() }?.let(objectMapper::readTree)
            ?: throw ConflictException("${QontakApi.LABEL} tak menjawab permintaan $url")
    }

    /** Satu elemen `data[]` → [RemoteTemplate]; null bila tak punya nama (tak bisa dipakai). */
    private fun parse(el: JsonNode): RemoteTemplate? {
        val name = el.get("name")?.takeIf { !it.isNull }?.asString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return RemoteTemplate(
            remoteId = el.get("id")?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() },
            name = name,
            language = el.get("language")?.takeIf { !it.isNull }?.asString()?.trim().orEmpty(),
            // Kosakata status & kategori Qontak sama persis dengan Meta pada respons nyata,
            // jadi pemetanya dipakai bersama — menyalinnya ke sini hanya akan membuat keduanya melenceng.
            category = category(el.get("category")?.takeIf { !it.isNull }?.asString()),
            status = status(el.get("status")?.takeIf { !it.isNull }?.asString()),
            bodyText = el.get("body")?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 5

        /** Contoh isian `{{1}}` untuk peninjau; tak pernah terkirim ke pelanggan. */
        const val EXAMPLE_VALUE = "Tagihan Agustus 2025 sebesar Rp350.000 jatuh tempo 10 Agustus"
    }
}
