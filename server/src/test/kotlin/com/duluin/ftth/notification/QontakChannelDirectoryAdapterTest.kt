package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.notification.adapter.outbound.messaging.QontakChannelDirectoryAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.tuple
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

/**
 * Isi dropdown "Channel WhatsApp" di kartu Gateway. Yang diuji adalah dua keputusan yang
 * menentukan apakah operator melihat pilihan yang benar: nama kanal diambil dari
 * `settings.account_name` (bukan `id` yang tak berarti bagi manusia), dan kanal nonaktif
 * disaring supaya tak dipilih lalu gagal mengirim jauh belakangan.
 */
class QontakChannelDirectoryAdapterTest {

    private fun fixture(): Pair<QontakChannelDirectoryAdapter, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return QontakChannelDirectoryAdapter(ObjectMapper(), builder.build()) to server
    }

    @Test
    fun `memakai account_name dan menyaring kanal nonaktif`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/v1/integrations?target_channel=wa")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer qontak-token"))
            .andRespond(
                withSuccess(
                    """
                    {"status":"success","data":[
                      {"id":"kanal-1","target_channel":"wa","is_active":true,
                       "settings":{"account_name":"CS Duluin 6281100001"}},
                      {"id":"kanal-mati","target_channel":"wa","is_active":false,
                       "settings":{"account_name":"Nomor lama"}},
                      {"id":"kanal-2","target_channel":"wa","name":"Cadangan","settings":{}},
                      {"id":"kanal-3","target_channel":"wa","is_active":true}
                    ]}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val channels = adapter.list("qontak-token")

        assertThat(channels).extracting("id", "name").containsExactly(
            tuple("kanal-1", "CS Duluin 6281100001"),
            // Tanpa `settings.account_name`, `name` dipakai; `is_active` absen = dianggap aktif,
            // supaya perubahan bentuk respons tak diam-diam mengosongkan seluruh dropdown.
            tuple("kanal-2", "Cadangan"),
            // Tanpa keduanya, id-lah yang tampil — jelek tapi masih bisa dipilih.
            tuple("kanal-3", "kanal-3"),
        )
        server.verify()
    }

    @Test
    fun `token salah jadi ConflictException bukan daftar kosong`() {
        val (adapter, server) = fixture()
        server.expect(requestTo(containsString("/v1/integrations")))
            .andRespond(
                withStatus(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":"invalid access token"}"""),
            )

        assertThatThrownBy { adapter.list("salah") }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("Mekari Qontak menolak (401)")
    }
}
