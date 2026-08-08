package com.duluin.ftth.billing.adapter.outbound.gateway.pivot

import com.duluin.ftth.billing.application.port.outbound.BalanceSnapshot
import com.duluin.ftth.billing.application.port.outbound.PayoutCommand
import com.duluin.ftth.billing.application.port.outbound.PayoutDispatch
import com.duluin.ftth.billing.application.port.outbound.PivotBalanceUsecase
import com.duluin.ftth.billing.application.port.outbound.PivotPayoutPort
import com.duluin.ftth.billing.domain.model.PivotMasterContext
import com.duluin.ftth.common.domain.error.ConflictException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.math.RoundingMode

/**
 * Adapter port penyaluran dana Pivot (`/v1/payouts`, `/v1/withdrawals`, `/v1/balances`) di atas
 * [PivotApiClient]. SEMUA panggilan memakai kredensial akun MASTER platform; aksi tenant (payout
 * beneficiary & withdrawal KYC) ditembak on-behalf sub-account (`x-submerchant-id`).
 *
 * Nominal `amount.value` bilangan bulat rupiah (IDR zero-decimal). Saldo dikembalikan Pivot sebagai
 * string desimal 2-angka (mis. `"4440916697.16"`) → dibulatkan ke bawah jadi rupiah utuh.
 */
@Component
class PivotPayoutGateway(
    private val apiClient: PivotApiClient,
) : PivotPayoutPort {

    override fun payout(
        master: PivotMasterContext,
        subMerchantId: String,
        command: PayoutCommand,
        requestId: String,
    ): PayoutDispatch = apiClient
        .post("/v1/payouts", command.toPayoutBody(), master.credentials(), subMerchantId = subMerchantId, requestId = requestId)
        .toDispatch()

    override fun withdraw(
        master: PivotMasterContext,
        subMerchantId: String,
        command: PayoutCommand,
        requestId: String,
    ): PayoutDispatch = apiClient
        .post("/v1/withdrawals", command.toWithdrawBody(), master.credentials(), subMerchantId = subMerchantId, requestId = requestId)
        .toDispatch()

    override fun transferToPayoutBalance(
        master: PivotMasterContext,
        subMerchantId: String,
        amountMinor: Long,
        referenceId: String,
        description: String?,
        requestId: String,
    ) {
        apiClient.post(
            "/v1/withdrawals",
            balanceTransferBody(amountMinor, referenceId, description),
            master.credentials(),
            subMerchantId = subMerchantId,
            requestId = requestId,
        )
    }

    override fun balance(
        master: PivotMasterContext,
        subMerchantId: String?,
        usecase: PivotBalanceUsecase,
    ): BalanceSnapshot = apiClient
        .get(balancePath(usecase), master.credentials(), subMerchantId = subMerchantId)
        .toBalance()

    /** `GET /v1/balances?usecase=…` — dompet dipilih eksplisit, jangan andalkan default Pivot. */
    internal fun balancePath(usecase: PivotBalanceUsecase): String = "/v1/balances?usecase=$usecase"

    /** Baca `data.availableBalance.{value,currency}`; toleran bila Pivot memipihkan bentuknya. */
    internal fun JsonNode.toBalance(): BalanceSnapshot {
        val avail = dataOrRoot().get("availableBalance")?.takeIf { !it.isNull } ?: dataOrRoot()
        return BalanceSnapshot(
            availableMinor = avail.wholeRupiah("value"),
            currency = avail.textOrNull("currency") ?: "IDR",
        )
    }

    /**
     * Body create payout terdokumentasi: array `payouts` dgn `inquiryId` (bila ada) atau
     * `channelInformation`.
     *
     * `amount.value` WAJIB string. Pernah dikirim sebagai angka JSON dan Pivot menolak semua payout
     * `400 field_format_invalid` — "Make sure value format is correct".
     */
    internal fun PayoutCommand.toPayoutBody(): Map<String, Any?> = mapOf(
        "payouts" to listOf(
            buildMap {
                put("referenceId", referenceId)
                put("amount", mapOf("value" to amountMinor.toString(), "currency" to "IDR"))
                payoutDescription(description)?.let { put("description", it) }
                if (inquiryId != null) {
                    put("inquiryId", inquiryId)
                } else {
                    channelCode?.let { put("channelCode", it) }
                    put(
                        "channelInformation",
                        buildMap {
                            accountNumber?.let { put("accountNumber", it) }
                            accountName?.let { put("accountName", it) }
                        },
                    )
                }
            },
        ),
    )

    /**
     * Body withdrawal KYC (`POST /v1/withdrawals`) — cairkan saldo PAYMENT ke rekening yang SUDAH
     * terdaftar di sub-account (dikirim sebagai `bankAccount` saat create). Karena itu spec tak
     * menerima `channelCode`/`accountNumber`/`inquiryId`: tujuannya sudah melekat pada akun.
     * `balanceType` sengaja tak diisi — itu hanya wajib untuk `withdrawType = BALANCE_TRANSFER`.
     */
    internal fun PayoutCommand.toWithdrawBody(): Map<String, Any?> = buildMap {
        put("referenceId", referenceId)
        put("withdrawType", "BANK_TRANSFER")
        put("isFullAmount", false)
        put("amount", mapOf("value" to amountMinor.toString(), "currency" to "IDR"))
        description?.take(DESCRIPTION_MAX)?.let { put("description", it) }
    }

    /**
     * Body pindah saldo PAYMENT → PAYOUT (`POST /v1/withdrawals` `withdrawType=BALANCE_TRANSFER`).
     * Endpoint yang sama dengan pencairan KYC, bedanya cuma [balanceType]: `BANK_TRANSFER` keluar ke
     * rekening bank, `BALANCE_TRANSFER` pindah antar-dompet sendiri.
     */
    internal fun balanceTransferBody(amountMinor: Long, referenceId: String, description: String?): Map<String, Any?> =
        buildMap {
            put("referenceId", referenceId)
            put("withdrawType", "BALANCE_TRANSFER")
            put("balanceType", "PAYOUT_BALANCE")
            put("isFullAmount", false)
            put("amount", mapOf("value" to amountMinor.toString(), "currency" to "IDR"))
            description?.take(DESCRIPTION_MAX)?.let { put("description", it) }
        }

    /**
     * `description` payout jauh lebih ketat daripada withdrawal: maks 20 karakter DAN alfanumerik
     * saja (spasi dipakai contoh resmi Pivot, jadi ikut dipertahankan). Dibersihkan diam-diam, bukan
     * ditolak — catatan kosmetik tak layak menggagalkan penyaluran uang.
     */
    internal fun payoutDescription(raw: String?): String? = raw
        ?.filter { it.isLetterOrDigit() || it == ' ' }
        ?.trim()
        ?.take(PAYOUT_DESCRIPTION_MAX)
        ?.takeIf { it.isNotEmpty() }

    private fun PivotMasterContext.credentials() = PivotCredentials(merchantId, merchantSecret, sandbox)

    private fun JsonNode.toDispatch(): PayoutDispatch {
        val data = dataOrRoot()
        val ref = data.textOrNull("uuid") ?: data.textOrNull("id")
            ?: data.textOrNull("referenceId") ?: data.textOrNull("reference")
            ?: throw ConflictException("Respons penyaluran Pivot tak berisi referensi")
        val status = data.textOrNull("status")?.uppercase()
        return PayoutDispatch(reference = ref, settledImmediately = status in SETTLED_STATUSES)
    }

    private fun JsonNode.dataOrRoot(): JsonNode = get("data")?.takeIf { !it.isNull } ?: this

    private fun JsonNode.textOrNull(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() }

    /** Baca nominal saldo (string/angka desimal) → rupiah utuh (floor). 0 bila tak terbaca. */
    private fun JsonNode.wholeRupiah(field: String): Long {
        val v = get(field)?.takeIf { !it.isNull } ?: return 0
        val dec = v.asString().toBigDecimalOrNull() ?: return 0
        return dec.setScale(0, RoundingMode.FLOOR).toLong()
    }

    private companion object {
        val SETTLED_STATUSES = setOf("SUCCESS", "COMPLETED", "SETTLED", "PAID")

        /** Batas `description` withdrawal menurut spec Pivot (1–50). */
        const val DESCRIPTION_MAX = 50

        /** Batas `description` payout — jauh lebih pendek daripada withdrawal (1–20). */
        const val PAYOUT_DESCRIPTION_MAX = 20
    }
}
