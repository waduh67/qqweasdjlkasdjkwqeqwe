package com.duluin.ftth.billing

import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotApiClient
import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotPayoutGateway
import com.duluin.ftth.billing.application.port.outbound.PayoutCommand
import com.duluin.ftth.billing.application.port.outbound.PivotBalanceUsecase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * Uji bentuk request/respons penyaluran dana Pivot tanpa HTTP: pemilihan dompet saldo, parsing
 * `availableBalance`, dan body `POST /v1/withdrawals`.
 *
 * Regresi utama: saldo pernah dibaca dari `/v1/payouts/balance` (dompet DISBURSEMENT) sehingga
 * tenant selalu melihat Rp 0 padahal dananya ada di dompet PAYMENT.
 */
class PivotPayoutGatewayTest {

    private val objectMapper = JsonMapper.builder().build()

    // Semua yang diuji fungsi murni; PivotApiClient hanya melengkapi konstruktor (tak menyentuh HTTP).
    private val gateway = PivotPayoutGateway(PivotApiClient(objectMapper))

    private fun command(description: String? = "Pencairan Agustus") = PayoutCommand(
        amountMinor = 268_000,
        channelCode = "BCA",
        accountNumber = "1234567890",
        accountName = "PT Contoh",
        inquiryId = "inq_1",
        referenceId = "0198f3a1-0000-7000-8000-000000000001",
        description = description,
    )

    // --- pemilihan dompet ---

    @Test
    fun `saldo yang ditampilkan tenant dibaca dari dompet PAYMENT`() {
        // Bukan /v1/payouts/balance: itu dompet DISBURSEMENT dan isinya 0 untuk sub-account penagih.
        assertThat(gateway.balancePath(PivotBalanceUsecase.PAYMENT)).isEqualTo("/v1/balances?usecase=PAYMENT")
    }

    @Test
    fun `guard payout memakai dompet DISBURSEMENT`() {
        assertThat(gateway.balancePath(PivotBalanceUsecase.DISBURSEMENT))
            .isEqualTo("/v1/balances?usecase=DISBURSEMENT")
    }

    // --- parsing saldo ---

    @Test
    fun `availableBalance dibaca dan dibulatkan ke bawah jadi rupiah utuh`() {
        val node = objectMapper.readTree(
            """{"code":"00","message":"Success","data":{"availableBalance":{"currency":"IDR","value":"268000.99"}}}""",
        )

        val snapshot = with(gateway) { node.toBalance() }

        assertThat(snapshot.availableMinor).isEqualTo(268_000)
        assertThat(snapshot.currency).isEqualTo("IDR")
    }

    @Test
    fun `nilai numerik non-string tetap terbaca`() {
        // /v1/balances/sub-merchants mengembalikan angka, bukan string desimal.
        val node = objectMapper.readTree("""{"data":{"availableBalance":{"value":268000,"currency":"IDR"}}}""")

        assertThat(with(gateway) { node.toBalance() }.availableMinor).isEqualTo(268_000)
    }

    @Test
    fun `saldo kosong jadi nol rupiah dan mata uang jatuh ke IDR`() {
        val node = objectMapper.readTree("""{"data":{}}""")

        val snapshot = with(gateway) { node.toBalance() }

        assertThat(snapshot.availableMinor).isZero()
        assertThat(snapshot.currency).isEqualTo("IDR")
    }

    // --- body withdrawal ---

    @Test
    fun `body withdrawal mengikuti spec Pivot`() {
        val body = with(gateway) { command().toWithdrawBody() }

        assertThat(body["referenceId"]).isEqualTo("0198f3a1-0000-7000-8000-000000000001")
        assertThat(body["withdrawType"]).isEqualTo("BANK_TRANSFER")
        assertThat(body["isFullAmount"]).isEqualTo(false)
        assertThat(body["description"]).isEqualTo("Pencairan Agustus")
        // Nominal string, rupiah utuh (IDR zero-decimal).
        assertThat(body["amount"]).isEqualTo(mapOf("value" to "268000", "currency" to "IDR"))
    }

    @Test
    fun `body withdrawal tak mengirim field di luar spec`() {
        // Rekening tujuan sudah melekat di sub-account (`bankAccount` saat create), jadi Pivot tak
        // menerima channelCode/accountNumber/inquiryId di sini — dan `remarks` bukan nama fieldnya.
        val body = with(gateway) { command().toWithdrawBody() }

        assertThat(body.keys).containsExactlyInAnyOrder(
            "referenceId", "withdrawType", "isFullAmount", "amount", "description",
        )
    }

    @Test
    fun `description dipangkas ke batas 50 karakter dan dilewati bila kosong`() {
        val long = with(gateway) { command(description = "x".repeat(80)).toWithdrawBody() }
        assertThat(long["description"] as String).hasSize(50)

        val none = with(gateway) { command(description = null).toWithdrawBody() }
        assertThat(none).doesNotContainKey("description")
    }

    // --- body payout ---

    @Suppress("UNCHECKED_CAST")
    private fun payoutBody(description: String? = "Pencairan Agustus") =
        (with(gateway) { command(description).toPayoutBody() }["payouts"] as List<Map<String, Any?>>).single()

    @Test
    fun `body payout memakai inquiryId bila ada`() {
        val payout = payoutBody()

        assertThat(payout["inquiryId"]).isEqualTo("inq_1")
        assertThat(payout).doesNotContainKey("channelInformation")
    }

    @Test
    fun `nominal payout dikirim sebagai string, bukan angka`() {
        // Angka JSON ditolak Pivot 400 field_format_invalid "Make sure value format is correct" —
        // dan itu menggagalkan SEMUA payout.
        assertThat(payoutBody()["amount"]).isEqualTo(mapOf("value" to "268000", "currency" to "IDR"))
    }

    @Test
    fun `description payout dipangkas ke 20 karakter dan dibersihkan dari non-alfanumerik`() {
        // Spec payout jauh lebih ketat daripada withdrawal: 1-20 karakter, alfanumerik saja.
        assertThat(payoutBody("Pencairan Agustus 2026 — termin ke-2")["description"])
            .isEqualTo("Pencairan Agustus 20")

        assertThat(payoutBody(null)).doesNotContainKey("description")
        // Deskripsi yang isinya cuma tanda baca habis dibersihkan → jangan kirim field kosong.
        assertThat(payoutBody("!!! ---")).doesNotContainKey("description")
    }

    // --- body pindah saldo PAYMENT → PAYOUT ---

    @Test
    fun `body balance transfer memakai withdrawType dan balanceType yang benar`() {
        val body = gateway.balanceTransferBody(500_000, "trf-1", "Isi saldo payout")

        assertThat(body["withdrawType"]).isEqualTo("BALANCE_TRANSFER")
        // balanceType WAJIB di sini — inilah yang membedakannya dari pencairan ke rekening bank.
        assertThat(body["balanceType"]).isEqualTo("PAYOUT_BALANCE")
        assertThat(body["referenceId"]).isEqualTo("trf-1")
        assertThat(body["isFullAmount"]).isEqualTo(false)
        assertThat(body["amount"]).isEqualTo(mapOf("value" to "500000", "currency" to "IDR"))
    }
}
