package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.outbound.InquiryResult
import com.duluin.ftth.billing.application.port.outbound.InquiryStatus
import com.duluin.ftth.billing.application.service.requireAccountName
import com.duluin.ftth.billing.application.service.requireValid
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Kebijakan penerimaan hasil `POST /v1/inquiry-account`. Hanya VALID yang boleh jadi payout —
 * transfer ke rekening salah tak bisa ditarik balik, jadi WARNING (nomor ada, nama beda) pun ditahan.
 */
class PivotInquiryPolicyTest {

    private fun result(status: InquiryStatus, detail: String? = null) =
        InquiryResult(inquiryId = "inq_1", status = status, detail = detail)

    @Test
    fun `hasil VALID diteruskan apa adanya`() {
        val valid = result(InquiryStatus.VALID)

        assertThat(valid.requireValid()).isSameAs(valid)
    }

    @Test
    fun `WARNING ditahan dan meneruskan nama versi bank`() {
        assertThatThrownBy { result(InquiryStatus.WARNING, "Bank record: Dummy Simulation").requireValid() }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("tak cocok catatan bank")
            .hasMessageContaining("Dummy Simulation")
    }

    @Test
    fun `INVALID dan PENDING ditahan dengan pesan masing-masing`() {
        assertThatThrownBy { result(InquiryStatus.INVALID, "Account number not found.").requireValid() }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("Rekening tak ditemukan")

        assertThatThrownBy { result(InquiryStatus.PENDING).requireValid() }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("masih diproses")
    }

    @Test
    fun `nama pemilik dipangkas spasinya dan wajib diisi`() {
        assertThat(requireAccountName("  Dummy Simulation  ")).isEqualTo("Dummy Simulation")

        assertThatThrownBy { requireAccountName("   ") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("wajib diisi")
        assertThatThrownBy { requireAccountName(null) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `nama pemilik lebih dari 60 karakter ditolak sebelum menembak Pivot`() {
        assertThat(requireAccountName("x".repeat(60))).hasSize(60)

        assertThatThrownBy { requireAccountName("x".repeat(61)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("maksimal 60")
    }
}
