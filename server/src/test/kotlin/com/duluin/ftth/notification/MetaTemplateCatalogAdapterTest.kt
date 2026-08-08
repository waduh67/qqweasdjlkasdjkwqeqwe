package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.notification.adapter.outbound.messaging.MetaTemplateCatalogAdapter
import com.duluin.ftth.notification.application.port.outbound.TemplateDraft
import com.duluin.ftth.notification.domain.model.TemplateApi
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

/**
 * Menguji CRUD template Meta lewat Graph API: pemetaan status/kategori ke enum domain,
 * penelusuran paging, dan tiga aturan Meta yang mudah dilanggar diam-diam — `example.body_text`
 * wajib ada saat membuat, hanya APPROVED/REJECTED yang boleh disunting, dan `hsm_id` wajib
 * ikut saat menghapus. Graph API dipalsukan lewat [MockRestServiceServer] — tak ada jaringan,
 * tak ada context Spring.
 *
 * Bagian yang paling rawan pada pembacaan: Meta mendokumentasikan SEPULUH status sementara
 * domain hanya punya enam, jadi pemetaan itulah yang paling banyak diperiksa di sini.
 */
class MetaTemplateCatalogAdapterTest {

    private val api = TemplateApi.Meta(wabaId = "9988", accessToken = "EAAtoken")

    private fun fixture(): Pair<MetaTemplateCatalogAdapter, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return MetaTemplateCatalogAdapter(ObjectMapper(), builder.build()) to server
    }

    @Test
    fun `memetakan satu template lengkap beserta teks BODY`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/9988/message_templates")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer EAAtoken"))
            .andRespond(withSuccess(page(TAGIHAN), MediaType.APPLICATION_JSON))

        val templates = adapter.list(api)

        assertThat(templates).hasSize(1)
        val t = templates.single()
        assertThat(t.remoteId).isEqualTo("111")
        assertThat(t.name).isEqualTo("tagihan_jatuh_tempo")
        assertThat(t.language).isEqualTo("id")
        assertThat(t.category).isEqualTo(TemplateCategory.UTILITY)
        assertThat(t.status).isEqualTo(TemplateStatus.APPROVED)
        assertThat(t.bodyText).isEqualTo("Halo, {{1}}")
        server.verify()
    }

    @Test
    fun `status Meta di luar enam nilai domain jatuh ke padanan terdekat`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("message_templates")))
            .andRespond(
                withSuccess(
                    page(
                        row("1", "a", status = "IN_APPEAL"),
                        row("2", "b", status = "LIMIT_EXCEEDED"),
                        row("3", "c", status = "ARCHIVED"),
                        row("4", "d", status = "DELETED"),
                        row("5", "e", status = "PENDING_DELETION"),
                        row("6", "f", status = "SOMETHING_NEW"),
                    ),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val byName = adapter.list(api).associate { it.name to it.status }

        // Banding = masih tertolak; kuota terlampaui = jeda sementara; sisanya tak bisa dikirim.
        assertThat(byName["a"]).isEqualTo(TemplateStatus.REJECTED)
        assertThat(byName["b"]).isEqualTo(TemplateStatus.PAUSED)
        assertThat(byName["c"]).isEqualTo(TemplateStatus.DISABLED)
        assertThat(byName["d"]).isEqualTo(TemplateStatus.DISABLED)
        assertThat(byName["e"]).isEqualTo(TemplateStatus.DISABLED)
        // Nilai enum baru yang belum kita kenal: UNKNOWN, bukan gagal seluruh sync.
        assertThat(byName["f"]).isEqualTo(TemplateStatus.UNKNOWN)
    }

    @Test
    fun `kategori tak dikenal dianggap UTILITY dan baris tanpa nama dilewati`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("message_templates")))
            .andRespond(
                withSuccess(
                    """
                    {"data":[
                      ${row("1", "lawas", category = "TRANSACTIONAL")},
                      ${row("2", "promo", category = "MARKETING")},
                      {"id":"3","language":"id","status":"APPROVED","category":"UTILITY"}
                    ]}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val templates = adapter.list(api)

        assertThat(templates.map { it.name }).containsExactly("lawas", "promo")
        assertThat(templates[0].category).isEqualTo(TemplateCategory.UTILITY)
        assertThat(templates[1].category).isEqualTo(TemplateCategory.MARKETING)
    }

    @Test
    fun `mengikuti paging next sampai habis`() {
        val (adapter, server) = fixture()
        val next = "https://graph.facebook.com/v21.0/9988/message_templates?after=CURSOR"
        server.expect(requestTo(containsString("message_templates?fields=")))
            .andRespond(
                withSuccess(
                    """{"data":[${row("1", "satu")}],"paging":{"next":"$next"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo(next))
            .andRespond(withSuccess(page(row("2", "dua")), MediaType.APPLICATION_JSON))

        val templates = adapter.list(api)

        assertThat(templates.map { it.name }).containsExactly("satu", "dua")
        server.verify()
    }

    @Test
    fun `penolakan Meta jadi ConflictException berisi pesannya`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("message_templates")))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"message":"Unsupported get request"}}"""),
            )

        assertThatThrownBy { adapter.list(TemplateApi.Meta("9988", "salah")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("Meta Cloud menolak (400)")
            .hasMessageContaining("Unsupported get request")
    }

    @Test
    fun `buat template mengirim contoh isian karena Meta menolak variabel tanpa example`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/9988/message_templates")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer EAAtoken"))
            .andExpect(jsonPath("$.name").value("tagihan_jatuh_tempo"))
            .andExpect(jsonPath("$.language").value("id"))
            .andExpect(jsonPath("$.category").value("UTILITY"))
            .andExpect(jsonPath("$.components[0].type").value("BODY"))
            .andExpect(jsonPath("$.components[0].text").value(BODY))
            // Bersarang DUA: array per-contoh berisi array per-variabel.
            .andExpect(jsonPath("$.components[0].example.body_text[0][0]").isNotEmpty)
            .andRespond(
                withSuccess(
                    """{"id":"555","status":"PENDING","category":"UTILITY"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val created = adapter.create(api, DRAFT)

        assertThat(created.remoteId).isEqualTo("555")
        assertThat(created.name).isEqualTo("tagihan_jatuh_tempo")
        assertThat(created.status).isEqualTo(TemplateStatus.PENDING)
        assertThat(created.bodyText).isEqualTo(BODY)
        server.verify()
    }

    @Test
    fun `sunting template PENDING ditolak sebelum permintaan dikirim`() {
        val (adapter, server) = fixture()
        // Satu-satunya panggilan: membaca status terkini. POST sunting tak pernah terjadi.
        server.expect(requestTo(containsString("/111?fields=")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(row("111", "tagihan_jatuh_tempo", status = "PENDING"), MediaType.APPLICATION_JSON),
            )

        assertThatThrownBy { adapter.edit(api, "111", DRAFT) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("PENDING")
        server.verify()
    }

    @Test
    fun `sunting template APPROVED memakai endpoint template tanpa nama dan bahasa`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/111?fields=")))
            .andRespond(withSuccess(row("111", "tagihan_jatuh_tempo"), MediaType.APPLICATION_JSON))
        server.expect(requestTo(containsString("/v21.0/111")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.category").value("UTILITY"))
            .andExpect(jsonPath("$.components[0].text").value(BODY))
            // Meta menolak permintaan sunting yang mencoba mengubah identitas template.
            .andExpect(jsonPath("$.name").doesNotExist())
            .andExpect(jsonPath("$.language").doesNotExist())
            .andRespond(withSuccess("""{"success":true}""", MediaType.APPLICATION_JSON))
        // Status baru (kembali antre peninjauan) hanya terbaca dengan menarik ulang.
        server.expect(requestTo(containsString("/111?fields=")))
            .andRespond(
                withSuccess(row("111", "tagihan_jatuh_tempo", status = "PENDING"), MediaType.APPLICATION_JSON),
            )

        val edited = adapter.edit(api, "111", DRAFT)

        assertThat(edited.status).isEqualTo(TemplateStatus.PENDING)
        server.verify()
    }

    @Test
    fun `hapus selalu menyertakan hsm_id agar bahasa lain tak ikut terhapus`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/9988/message_templates")))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(requestTo(containsString("name=tagihan_jatuh_tempo")))
            .andExpect(requestTo(containsString("hsm_id=111")))
            .andRespond(withSuccess("""{"success":true}""", MediaType.APPLICATION_JSON))

        adapter.delete(api, "111", "tagihan_jatuh_tempo")

        server.verify()
    }

    private companion object {
        const val BODY = "Halo, {{1}}"

        val DRAFT = TemplateDraft(
            name = "tagihan_jatuh_tempo",
            language = "id",
            category = TemplateCategory.UTILITY,
            bodyText = BODY,
        )

        val TAGIHAN = """
            {"id":"111","name":"tagihan_jatuh_tempo","language":"id","status":"APPROVED",
             "category":"UTILITY","components":[
               {"type":"HEADER","format":"TEXT","text":"Tagihan"},
               {"type":"BODY","text":"Halo, {{1}}"},
               {"type":"FOOTER","text":"Terima kasih"}]}
        """.trimIndent()

        fun row(id: String, name: String, status: String = "APPROVED", category: String = "UTILITY") =
            """{"id":"$id","name":"$name","language":"id","status":"$status","category":"$category"}"""

        fun page(vararg rows: String) = """{"data":[${rows.joinToString(",")}]}"""
    }
}
