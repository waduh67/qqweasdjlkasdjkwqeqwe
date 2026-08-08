package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.notification.adapter.outbound.messaging.QontakTemplateCatalogAdapter
import com.duluin.ftth.notification.application.port.outbound.TemplateDraft
import com.duluin.ftth.notification.domain.model.TemplateApi
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
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
 * Menguji katalog template Mekari Qontak terhadap bentuk respons NYATA Open API mereka —
 * yang berbeda dari Meta di tiga hal yang mudah salah:
 *
 *  1. isi BODY datang sebagai STRING tunggal di field `body`, bukan array `components`;
 *  2. saat MEMBUAT, isi justru harus dibungkus `attributes[].components[]` dengan `example`
 *     hanya SATU tingkat array (Meta dua tingkat);
 *  3. paging `offset` tak jelas basisnya (0 atau 1), jadi hasilnya harus benar untuk kedua tafsir.
 *
 * Plus batas kemampuannya: Qontak tak punya endpoint ubah maupun hapus, dan itu harus terdengar
 * jelas sebagai [ConflictException] — bukan panggilan HTTP yang mengarang endpoint.
 */
class QontakTemplateCatalogAdapterTest {

    private val api = TemplateApi.Qontak(accessToken = "qontak-token", channelIntegrationId = "kanal-1")

    private fun fixture(): Pair<QontakTemplateCatalogAdapter, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return QontakTemplateCatalogAdapter(ObjectMapper(), builder.build()) to server
    }

    @Test
    fun `membaca body sebagai string tunggal dan kosakata status Meta`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/v1/templates/whatsapp")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer qontak-token"))
            .andRespond(withSuccess(page(TAGIHAN, PROMO), MediaType.APPLICATION_JSON))
        // Halaman kedua kosong → iterasi berhenti.
        server.expect(requestTo(containsString("offset=2")))
            .andRespond(withSuccess("""{"status":"success","data":[]}""", MediaType.APPLICATION_JSON))

        val templates = adapter.list(api)

        assertThat(templates).hasSize(2)
        val tagihan = templates.first()
        assertThat(tagihan.remoteId).isEqualTo("8f2c-uuid")
        assertThat(tagihan.name).isEqualTo("tagihan_jatuh_tempo")
        assertThat(tagihan.language).isEqualTo("id")
        assertThat(tagihan.category).isEqualTo(TemplateCategory.UTILITY)
        assertThat(tagihan.status).isEqualTo(TemplateStatus.APPROVED)
        assertThat(tagihan.bodyText).isEqualTo("Halo, {{1}}")
        // `header: null` & kategori MARKETING tak menggagalkan pembacaan baris lain.
        assertThat(templates[1].category).isEqualTo(TemplateCategory.MARKETING)
        server.verify()
    }

    @Test
    fun `paging offset yang diulang tak menghasilkan duplikat`() {
        val (adapter, server) = fixture()
        // Tafsir offset yang keliru bisa membuat halaman 2 mengembalikan isi halaman 1 lagi.
        server.expect(requestTo(containsString("/v1/templates/whatsapp")))
            .andRespond(withSuccess(page(TAGIHAN), MediaType.APPLICATION_JSON))
        server.expect(requestTo(containsString("offset=2")))
            .andRespond(withSuccess(page(TAGIHAN), MediaType.APPLICATION_JSON))

        val templates = adapter.list(api)

        // Dedup berdasarkan id, dan halaman tanpa baris baru menghentikan iterasi —
        // jadi halaman 3..5 tak pernah ditembak (server.verify menegakkannya).
        assertThat(templates).hasSize(1)
        server.verify()
    }

    @Test
    fun `buat template membungkus isi ke attributes dan menyusul mengambil id`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/v1/templates/whatsapp")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer qontak-token"))
            .andExpect(jsonPath("$.name").value("tagihan_jatuh_tempo"))
            .andExpect(jsonPath("$.category").value("UTILITY"))
            .andExpect(jsonPath("$.attributes[0].language").value("id"))
            .andExpect(jsonPath("$.attributes[0].components[0].type").value("BODY"))
            .andExpect(jsonPath("$.attributes[0].components[0].text").value(BODY))
            // SATU tingkat array — berbeda dari Meta yang bersarang dua.
            .andExpect(jsonPath("$.attributes[0].components[0].example.body_text[0]").isString)
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""{"status":"success"}"""))
        // Respons 201 tak memuat id, sementara mengirim butuh id → dicari lewat ?query=.
        server.expect(requestTo(containsString("query=tagihan_jatuh_tempo")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(page(TAGIHAN_PENDING), MediaType.APPLICATION_JSON))

        val created = adapter.create(api, DRAFT)

        assertThat(created.remoteId).isEqualTo("8f2c-uuid")
        assertThat(created.status).isEqualTo(TemplateStatus.PENDING)
        server.verify()
    }

    @Test
    fun `pencarian id yang gagal tak membatalkan template yang terlanjur diajukan`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/v1/templates/whatsapp")))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""{"status":"success"}"""))
        server.expect(requestTo(containsString("query=")))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(""))

        val created = adapter.create(api, DRAFT)

        // Template SUDAH ada di Qontak; melempar error di sini justru menyesatkan operator.
        assertThat(created.remoteId).isNull()
        assertThat(created.name).isEqualTo("tagihan_jatuh_tempo")
        assertThat(created.status).isEqualTo(TemplateStatus.PENDING)
        server.verify()
    }

    @Test
    fun `sunting dan hapus ditolak tanpa menembak jaringan`() {
        val (adapter, server) = fixture()
        // Tak satu pun expect didaftarkan: panggilan HTTP apa pun akan menggagalkan tes.

        assertThatThrownBy { adapter.edit(api, "8f2c-uuid", DRAFT) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("hapus template ini lalu buat yang baru")

        assertThatThrownBy { adapter.delete(api, "8f2c-uuid", "tagihan_jatuh_tempo") }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("dasbor Qontak")

        server.verify()
    }

    @Test
    fun `kredensial Meta yang tersasar ke adapter Qontak ditolak jelas`() {
        val (adapter, _) = fixture()

        assertThatThrownBy { adapter.list(TemplateApi.Meta("9988", "EAAtoken")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("tak cocok dengan penyedia")
    }

    @Test
    fun `penolakan Qontak jadi ConflictException berisi pesannya`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/v1/templates/whatsapp")))
            .andExpect(requestTo(not(containsString("offset"))))
            .andRespond(
                withStatus(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"invalid access token"}"""),
            )

        assertThatThrownBy { adapter.list(api) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("Mekari Qontak menolak (401)")
            .hasMessageContaining("invalid access token")
    }

    private companion object {
        const val BODY = "Halo, {{1}}"

        val DRAFT = TemplateDraft(
            name = "tagihan_jatuh_tempo",
            language = "id",
            category = TemplateCategory.UTILITY,
            bodyText = BODY,
        )

        /** Bentuk respons nyata Qontak: `body` string, `header` null, kosakata kategori Meta. */
        val TAGIHAN = """
            {"id":"8f2c-uuid","name":"tagihan_jatuh_tempo","language":"id","header":null,
             "body":"Halo, {{1}}","footer":"Terima kasih","buttons":[],
             "status":"APPROVED","category":"UTILITY","quality_rating":"GREEN"}
        """.trimIndent()

        val TAGIHAN_PENDING = TAGIHAN.replace("\"APPROVED\"", "\"PENDING\"")

        val PROMO = """
            {"id":"1a9b-uuid","name":"promo_lebaran","language":"id","header":null,
             "body":"Promo {{1}}","status":"APPROVED","category":"MARKETING"}
        """.trimIndent()

        fun page(vararg rows: String) = """{"status":"success","data":[${rows.joinToString(",")}]}"""
    }
}
