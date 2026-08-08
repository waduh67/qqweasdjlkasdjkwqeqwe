package com.duluin.ftth.notification.adapter.outbound.messaging

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.notification.application.port.outbound.RemoteTemplate
import com.duluin.ftth.notification.application.port.outbound.WhatsAppTemplateCatalog
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Pembaca katalog template dari Graph API Meta:
 * `GET /{waba-id}/message_templates?fields=id,name,language,status,category,components`.
 *
 * Bersifat TOLERAN terhadap bentuk respons: nilai `status`/`category` yang tak dikenal
 * dipetakan ke [TemplateStatus.UNKNOWN]/[TemplateCategory.UTILITY] dan baris tanpa `name`
 * dilewati, alih-alih menggagalkan seluruh sync. Meta menambah nilai enum baru dari waktu ke
 * waktu (mis. kategori lama TRANSACTIONAL→UTILITY), dan satu template aneh tak boleh membuat
 * operator kehilangan seluruh daftarnya.
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
    constructor(objectMapper: ObjectMapper) : this(objectMapper, MetaGraph.restClient())

    private val log = LoggerFactory.getLogger(javaClass)

    override fun list(wabaId: String, accessToken: String): List<RemoteTemplate> {
        val templates = mutableListOf<RemoteTemplate>()
        var url: String? = "${MetaGraph.BASE}/$wabaId/message_templates?fields=$FIELDS&limit=$PAGE_SIZE"
        var page = 0
        while (url != null && page < MAX_PAGES) {
            val node = fetch(url, accessToken)
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

    private fun fetch(url: String, accessToken: String): JsonNode {
        val raw = try {
            restClient.get()
                .uri(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .retrieve()
                .body(String::class.java)
        } catch (e: Exception) {
            throw ConflictException(MetaGraph.transportError("Meta Cloud", e))
        }
        return raw?.let(objectMapper::readTree)
            ?: throw ConflictException("Meta Cloud tak mengembalikan daftar template")
    }

    /** Satu elemen `data[]` → [RemoteTemplate]; null bila tak punya nama (tak bisa dipakai). */
    private fun parse(el: JsonNode): RemoteTemplate? {
        val name = el.get("name")?.takeIf { !it.isNull }?.asString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return RemoteTemplate(
            metaId = el.get("id")?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() },
            name = name,
            language = el.get("language")?.takeIf { !it.isNull }?.asString()?.trim().orEmpty(),
            category = category(el.get("category")?.takeIf { !it.isNull }?.asString()),
            status = status(el.get("status")?.takeIf { !it.isNull }?.asString()),
            bodyText = bodyText(el.get("components")),
        )
    }

    /**
     * Kategori tak dikenal dianggap UTILITY: itu kategori transaksional yang relevan di sini,
     * dan salah-tebak paling banter memasukkan satu template ekstra ke katalog (bukan
     * menyembunyikan yang dibutuhkan). Kategori lawas `TRANSACTIONAL` = UTILITY sekarang.
     */
    private fun category(value: String?): TemplateCategory = when (value?.uppercase()?.trim()) {
        "MARKETING" -> TemplateCategory.MARKETING
        "AUTHENTICATION", "OTP" -> TemplateCategory.AUTHENTICATION
        else -> TemplateCategory.UTILITY
    }

    /**
     * Meta mendokumentasikan sepuluh status; domain kita hanya menyimpan enam (lihat CHECK di
     * V75). Lima sisanya dipetakan ke padanan terdekat menurut *bisa-tidaknya dikirim*, BUKAN
     * ke UNKNOWN — di UI, UNKNOWN berarti "belum pernah disinkron", jadi memakainya untuk baris
     * yang barusan ditarik dari Meta justru menyesatkan operator.
     *
     * Nilai di luar daftar (Meta menambah enum baru dari waktu ke waktu) tetap jatuh ke UNKNOWN.
     */
    private fun status(value: String?): TemplateStatus = when (value?.uppercase()?.trim()) {
        "APPROVED" -> TemplateStatus.APPROVED
        "PENDING" -> TemplateStatus.PENDING
        // IN_APPEAL selalu menyusul penolakan: template tetap tak bisa dipakai selama banding.
        "REJECTED", "IN_APPEAL" -> TemplateStatus.REJECTED
        // Jeda sementara karena mutu/kuota — bisa pulih sendiri, beda dari dinonaktifkan permanen.
        "PAUSED", "LIMIT_EXCEEDED" -> TemplateStatus.PAUSED
        // Sudah tak dapat dikirim dan tak akan pulih tanpa tindakan di Meta Business Manager.
        "DISABLED", "ARCHIVED", "DELETED", "PENDING_DELETION" -> TemplateStatus.DISABLED
        else -> TemplateStatus.UNKNOWN
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
    }
}
