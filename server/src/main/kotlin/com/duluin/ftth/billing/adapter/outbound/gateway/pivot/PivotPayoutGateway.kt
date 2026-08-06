package com.duluin.ftth.billing.adapter.outbound.gateway.pivot

import com.duluin.ftth.billing.application.port.outbound.BalanceSnapshot
import com.duluin.ftth.billing.application.port.outbound.PayoutCommand
import com.duluin.ftth.billing.application.port.outbound.PayoutDispatch
import com.duluin.ftth.billing.application.port.outbound.PivotPayoutPort
import com.duluin.ftth.billing.domain.model.PivotMasterContext
import com.duluin.ftth.common.domain.error.ConflictException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * Adapter port penyaluran dana Pivot (`/v1/payouts`, `/v1/withdrawals`, `/v1/balances`) di atas
 * [PivotApiClient]. SEMUA panggilan memakai kredensial akun MASTER platform; withdrawal KYC
 * ditembak on-behalf sub-account tenant (`x-submerchant-id`).
 *
 * Nominal IDR zero-decimal — `amount.value` bilangan bulat (minor-unit). Bentuk respons Pivot
 * dibaca defensif (beberapa alias field) karena sandbox/prod kadang beda pembungkusnya.
 */
@Component
class PivotPayoutGateway(
    private val apiClient: PivotApiClient,
) : PivotPayoutPort {

    override fun payout(master: PivotMasterContext, command: PayoutCommand, requestId: String): PayoutDispatch =
        apiClient.post("/v1/payouts", command.toBody(), master.credentials(), requestId = requestId).toDispatch()

    override fun withdraw(
        master: PivotMasterContext,
        subMerchantId: String,
        command: PayoutCommand,
        requestId: String,
    ): PayoutDispatch = apiClient
        .post("/v1/withdrawals", command.toBody(), master.credentials(), subMerchantId = subMerchantId, requestId = requestId)
        .toDispatch()

    override fun balance(master: PivotMasterContext, subMerchantId: String?): BalanceSnapshot {
        val node = apiClient.get("/v1/balances?usecase=PAYMENT", master.credentials(), subMerchantId = subMerchantId)
        val data = node.dataOrRoot().balanceEntry()
        return BalanceSnapshot(
            availableMinor = data.longOrZero("availableBalance", "available", "balance", "value"),
            pendingMinor = data.longOrZero("pendingBalance", "pending", "holdBalance"),
            currency = data.textOrNull("currency") ?: "IDR",
        )
    }

    private fun PayoutCommand.toBody(): Map<String, Any?> = buildMap {
        put("amount", mapOf("value" to amountMinor, "currency" to "IDR"))
        inquiryId?.let { put("inquiryId", it) }
        channelCode?.let { put("channelCode", it) }
        accountNumber?.let { put("accountNumber", it) }
        remarks?.let { put("remarks", it) }
    }

    private fun PivotMasterContext.credentials() = PivotCredentials(merchantId, merchantSecret, sandbox)

    private fun JsonNode.toDispatch(): PayoutDispatch {
        val data = dataOrRoot()
        val ref = data.textOrNull("id") ?: data.textOrNull("referenceId") ?: data.textOrNull("reference")
            ?: throw ConflictException("Respons penyaluran Pivot tak berisi referensi")
        val status = data.textOrNull("status")?.uppercase()
        return PayoutDispatch(reference = ref, settledImmediately = status in SETTLED_STATUSES)
    }

    private fun JsonNode.dataOrRoot(): JsonNode = get("data")?.takeIf { !it.isNull } ?: this

    /** `/v1/balances` bisa balikan objek tunggal atau array (per-currency) — ambil entri IDR/pertama. */
    private fun JsonNode.balanceEntry(): JsonNode {
        if (!isArray) return this
        return firstOrNull { it.textOrNull("currency")?.equals("IDR", ignoreCase = true) == true }
            ?: firstOrNull()
            ?: this
    }

    private fun JsonNode.textOrNull(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() }

    private fun JsonNode.longOrZero(vararg fields: String): Long {
        for (field in fields) {
            val v = get(field) ?: continue
            if (v.isNull) continue
            if (v.isNumber) return v.asLong()
            v.asString().toLongOrNull()?.let { return it }
        }
        return 0
    }

    private companion object {
        val SETTLED_STATUSES = setOf("SUCCESS", "COMPLETED", "SETTLED", "PAID")
    }
}
