package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotApiClient
import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotSubMerchantGateway
import com.duluin.ftth.billing.application.port.outbound.InquiryStatus
import com.duluin.ftth.common.domain.error.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * Uji bentuk request/respons `POST /v1/inquiry-account` tanpa HTTP.
 *
 * Regresi utama: body pernah dikirim PIPIH (`accountNumber` di akar, tanpa `accountName`) sehingga
 * Pivot menolak 400 `field_required` dengan pesan berlubang "Make sure  value is fulfilled" dan
 * SEMUA payout gagal. Respons juga pernah dibaca dari `data.inquiryId`, padahal Pivot memakai
 * `data.uuid` + `data.inquiryResult`.
 */
class PivotSubMerchantGatewayTest {

    private val objectMapper = JsonMapper.builder().build()

    // Semua yang diuji fungsi murni; PivotApiClient hanya melengkapi konstruktor (tak menyentuh HTTP).
    private val gateway = PivotSubMerchantGateway(PivotApiClient(objectMapper))

    // --- body request ---

    @Test
    fun `body inquiry bersarang di channelInformation`() {
        val body = gateway.inquiryBody("BCA", "999966660001", "Dummy Simulation")

        assertThat(body["channelCode"]).isEqualTo("BCA")
        assertThat(body["channelInformation"])
            .isEqualTo(mapOf("accountNumber" to "999966660001", "accountName" to "Dummy Simulation"))
    }

    @Test
    fun `body inquiry tak menaruh rekening di akar`() {
        // Bentuk pipih {channelCode, accountNumber} ditolak Pivot 400 "Make sure  value is fulfilled".
        val body = gateway.inquiryBody("BCA", "999966660001", "Dummy Simulation")

        assertThat(body.keys).containsExactlyInAnyOrder("channelCode", "channelInformation")
    }

    // --- parsing respons ---

    @Test
    fun `inquiryId dibaca dari data uuid`() {
        val node = objectMapper.readTree(
            """{"uuid":"9062cec0-a8d6-42a9-88d1-e0d8d6f487b1","inquiryResult":{"status":"VALID"}}""",
        )

        val result = with(gateway) { node.toInquiry() }

        assertThat(result.inquiryId).isEqualTo("9062cec0-a8d6-42a9-88d1-e0d8d6f487b1")
        assertThat(result.status).isEqualTo(InquiryStatus.VALID)
        assertThat(result.detail).isNull()
    }

    @Test
    fun `status WARNING membawa catatan bank apa adanya`() {
        val node = objectMapper.readTree(
            """{"uuid":"9062cec0","inquiryResult":{"status":"WARNING","detail":"…Bank record: Dummy Simulation"}}""",
        )

        val result = with(gateway) { node.toInquiry() }

        assertThat(result.status).isEqualTo(InquiryStatus.WARNING)
        assertThat(result.detail).isEqualTo("…Bank record: Dummy Simulation")
    }

    @Test
    fun `status INVALID terpetakan`() {
        val node = objectMapper.readTree("""{"uuid":"x","inquiryResult":{"status":"INVALID","detail":"Account number not found."}}""")

        assertThat(with(gateway) { node.toInquiry() }.status).isEqualTo(InquiryStatus.INVALID)
    }

    @Test
    fun `status tak dikenal atau absen dianggap PENDING, bukan VALID`() {
        // Jangan pernah meloloskan payout berdasarkan hasil validasi yang tak dimengerti.
        val unknown = objectMapper.readTree("""{"uuid":"x","inquiryResult":{"status":"SOMETHING_NEW"}}""")
        val missing = objectMapper.readTree("""{"uuid":"x"}""")

        assertThat(with(gateway) { unknown.toInquiry() }.status).isEqualTo(InquiryStatus.PENDING)
        assertThat(with(gateway) { missing.toInquiry() }.status).isEqualTo(InquiryStatus.PENDING)
    }

    @Test
    fun `respons tanpa id ditolak`() {
        val node = objectMapper.readTree("""{"inquiryResult":{"status":"VALID"}}""")

        assertThatThrownBy { with(gateway) { node.toInquiry() } }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("tak berisi id")
    }
}
