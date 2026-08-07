package com.duluin.ftth.billing.adapter.outbound.gateway.pivot

import com.duluin.ftth.billing.application.port.outbound.BalanceSnapshot
import com.duluin.ftth.billing.application.port.outbound.PayoutCommand
import com.duluin.ftth.billing.application.port.outbound.PayoutDispatch
import com.duluin.ftth.billing.application.port.outbound.PivotPayoutPort
import com.duluin.ftth.billing.domain.model.PivotMasterContext
import com.duluin.ftth.common.domain.error.ConflictException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.math.RoundingMode

/**
 * Adapter port penyaluran dana Pivot (`/v1/payouts`, `/v1/withdrawals`, `/v1/payouts/balance`) di
 * atas [PivotApiClient]. SEMUA panggilan memakai kredensial akun MASTER platform; aksi tenant
 * (payout beneficiary & withdrawal KYC) ditembak on-behalf sub-account (`x-submerchant-id`).
 *
 * Nominal `amount.value` bilangan bulat rupiah (IDR zero-decimal). Saldo payout dikembalikan Pivot
 * sebagai string desimal 2-angka (mis. `"4440916697.16"`) → dibulatkan ke bawah jadi rupiah utuh.
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

    override fun balance(master: PivotMasterContext, subMerchantId: String?): BalanceSnapshot {
        val node = apiClient.get("/v1/payouts/balance?currency=IDR", master.credentials(), subMerchantId = subMerchantId)
        val avail = node.dataOrRoot().get("availableBalance")?.takeIf { !it.isNull } ?: node.dataOrRoot()
        return BalanceSnapshot(
            availableMinor = avail.wholeRupiah("value"),
            pendingMinor = 0,
            currency = avail.textOrNull("currency") ?: "IDR",
        )
    }

    /** Body create payout terdokumentasi: array `payouts` dgn `inquiryId` (bila ada) atau `channelInformation`. */
    private fun PayoutCommand.toPayoutBody(): Map<String, Any?> = mapOf(
        "payouts" to listOf(
            buildMap {
                put("referenceId", referenceId)
                put("amount", mapOf("value" to amountMinor, "currency" to "IDR"))
                description?.let { put("description", it) }
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

    /** Body withdrawal KYC (`/v1/withdrawals`) — bentuk flat lama, TAK berbagi builder dgn payout. */
    private fun PayoutCommand.toWithdrawBody(): Map<String, Any?> = buildMap {
        put("amount", mapOf("value" to amountMinor, "currency" to "IDR"))
        inquiryId?.let { put("inquiryId", it) }
        channelCode?.let { put("channelCode", it) }
        accountNumber?.let { put("accountNumber", it) }
        description?.let { put("remarks", it) }
    }

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
    }
}
