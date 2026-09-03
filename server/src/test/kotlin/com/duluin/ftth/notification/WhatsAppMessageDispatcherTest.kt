package com.duluin.ftth.notification

import com.duluin.ftth.notification.adapter.outbound.messaging.WhatsAppMessageDispatcher
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.WhatsAppGateway
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/**
 * Menguji jalur kirim Mekari Qontak, yang berbeda hakikatnya dari Meta: API broadcast
 * langsung Qontak HANYA menerima template. Karena itu pemicu tanpa template bukan jatuh ke
 * teks biasa (seperti Meta) melainkan [DeliveryStatus.SKIPPED] — dan itu harus terjadi TANPA
 * menembak jaringan sama sekali.
 */
class WhatsAppMessageDispatcherTest {

    private fun fixture(): Pair<WhatsAppMessageDispatcher, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return WhatsAppMessageDispatcher(builder.build()) to server
    }

    private fun qontak(templateId: String?) = WhatsAppGateway.Qontak(
        accessToken = "qontak-token",
        channelIntegrationId = "kanal-1",
        templateId = templateId,
        templateLang = "id",
    )

    @Test
    fun `Fonnte rejection in successful HTTP response is reported as failed`() {
        val (dispatcher, server) = fixture()
        server.expect(requestTo("https://api.fonnte.com/send"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "fonnte-token"))
            .andExpect(header("Content-Type", containsString("multipart/form-data")))
            .andExpect(content().string(containsString("name=\"target\"")))
            .andExpect(content().string(containsString("628111")))
            .andExpect(content().string(containsString("name=\"message\"")))
            .andExpect(content().string(containsString("pesan uji")))
            .andRespond(withSuccess("""{"status":false,"reason":"device offline"}""", MediaType.APPLICATION_JSON))

        val outcome = dispatcher.send(WhatsAppGateway.Fonnte("fonnte-token"), "628111", "Budi", "pesan uji")

        assertThat(outcome.status).isEqualTo(DeliveryStatus.FAILED)
        assertThat(outcome.detail).contains("Fonnte")
        assertThat(outcome.detail).contains("device offline")
        server.verify()
    }

    @Test
    fun `Fonnte rejection with legacy capital status is reported as failed`() {
        val (dispatcher, server) = fixture()
        server.expect(requestTo("https://api.fonnte.com/send"))
            .andRespond(withSuccess("""{"Status":false,"reason":"token invalid"}""", MediaType.APPLICATION_JSON))

        val outcome = dispatcher.send(WhatsAppGateway.Fonnte("fonnte-token"), "628111", "Budi", "pesan uji")

        assertThat(outcome.status).isEqualTo(DeliveryStatus.FAILED)
        assertThat(outcome.detail).contains("Fonnte menolak permintaan")
        assertThat(outcome.detail).contains("token invalid")
        server.verify()
    }

    @Test
    fun `Fonnte acceptance in successful HTTP response is reported as sent`() {
        val (dispatcher, server) = fixture()
        server.expect(requestTo("https://api.fonnte.com/send"))
            .andRespond(withSuccess("""{"status":true}""", MediaType.APPLICATION_JSON))

        val outcome = dispatcher.send(WhatsAppGateway.Fonnte("fonnte-token"), "628111", "Budi", "pesan uji")

        assertThat(outcome.status).isEqualTo(DeliveryStatus.SENT)
        assertThat(outcome.detail).contains("Fonnte")
        server.verify()
    }

    @Test
    fun `Fonnte response without boolean status is reported as failed`() {
        val (dispatcher, server) = fixture()
        server.expect(requestTo("https://api.fonnte.com/send"))
            .andRespond(withSuccess("""{"detail":"unknown"}""", MediaType.APPLICATION_JSON))

        val outcome = dispatcher.send(WhatsAppGateway.Fonnte("fonnte-token"), "628111", "Budi", "pesan uji")

        assertThat(outcome.status).isEqualTo(DeliveryStatus.FAILED)
        assertThat(outcome.detail).contains("Respons Fonnte tidak valid")
        server.verify()
    }

    @Test
    fun `Qontak tanpa template dilewati tanpa memanggil API`() {
        val (dispatcher, server) = fixture()
        // Tak ada expect: satu panggilan HTTP pun akan menggagalkan tes.

        val outcome = dispatcher.send(qontak(templateId = null), "628111", "Budi", "pesan")

        // SKIPPED, bukan FAILED: tak ada yang rusak dan tak ada gunanya dicoba ulang.
        assertThat(outcome.status).isEqualTo(DeliveryStatus.SKIPPED)
        assertThat(outcome.detail).contains("hanya bisa mengirim template")
        server.verify()
    }

    @Test
    fun `Qontak dengan template mengirim body sesuai spesifikasi broadcast langsung`() {
        val (dispatcher, server) = fixture()
        server.expect(requestTo(containsString("/v1/broadcasts/whatsapp/direct")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer qontak-token"))
            .andExpect(jsonPath("$.to_name").value("Budi"))
            // MSISDN internasional tanpa '+'.
            .andExpect(jsonPath("$.to_number").value("628111"))
            .andExpect(jsonPath("$.message_template_id").value("8f2c-uuid"))
            .andExpect(jsonPath("$.channel_integration_id").value("kanal-1"))
            .andExpect(jsonPath("$.language.code").value("id"))
            // Konvensi kita: satu variabel {{1}} berisi SELURUH pesan yang sudah dirakit.
            .andExpect(jsonPath("$.parameters.body[0].key").value("1"))
            .andExpect(jsonPath("$.parameters.body[0].value_text").value("Tagihan Anda jatuh tempo besok"))
            .andRespond(withSuccess("""{"status":"success"}""", MediaType.APPLICATION_JSON))

        val outcome = dispatcher.send(
            qontak(templateId = "8f2c-uuid"),
            "+628111",
            "Budi",
            "Tagihan Anda jatuh tempo besok",
        )

        assertThat(outcome.status).isEqualTo(DeliveryStatus.SENT)
        server.verify()
    }

    @Test
    fun `nama penerima kosong jatuh ke nomornya karena to_name wajib di Qontak`() {
        val (dispatcher, server) = fixture()
        server.expect(requestTo(containsString("/v1/broadcasts/whatsapp/direct")))
            .andExpect(jsonPath("$.to_name").value("628111"))
            .andRespond(withSuccess("""{"status":"success"}""", MediaType.APPLICATION_JSON))

        val outcome = dispatcher.send(qontak(templateId = "8f2c-uuid"), "628111", "   ", "pesan")

        assertThat(outcome.status).isEqualTo(DeliveryStatus.SENT)
        server.verify()
    }

    @Test
    fun `penolakan Qontak jadi FAILED yang layak dicoba ulang bukan lemparan`() {
        val (dispatcher, server) = fixture()
        server.expect(requestTo(containsString("/v1/broadcasts/whatsapp/direct")))
            .andRespond(
                withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"messages":["template not approved"]}}"""),
            )

        // Satu nomor gagal tak boleh menggagalkan seluruh batch broadcast.
        val outcome = dispatcher.send(qontak(templateId = "8f2c-uuid"), "628111", "Budi", "pesan")

        assertThat(outcome.status).isEqualTo(DeliveryStatus.FAILED)
        assertThat(outcome.detail).contains("Mekari Qontak menolak (422)")
        assertThat(outcome.detail).contains("template not approved")
    }

    @Test
    fun `Meta tetap jatuh ke teks biasa saat pemicu tak terpetakan`() {
        val (dispatcher, server) = fixture()
        server.expect(requestTo(containsString("/1234567890/messages")))
            .andExpect(jsonPath("$.type").value("text"))
            .andExpect(jsonPath("$.text.body").value("pesan"))
            .andRespond(withSuccess("""{"messages":[{"id":"wamid"}]}""", MediaType.APPLICATION_JSON))

        val meta = WhatsAppGateway.MetaCloud("1234567890", "EAAtoken", templateName = null, templateLang = "id")
        val outcome = dispatcher.send(meta, "628111", "Budi", "pesan")

        assertThat(outcome.status).isEqualTo(DeliveryStatus.SENT)
        server.verify()
    }
}
