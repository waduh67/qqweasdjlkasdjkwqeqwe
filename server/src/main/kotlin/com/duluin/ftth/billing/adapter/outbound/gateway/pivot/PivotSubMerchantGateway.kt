package com.duluin.ftth.billing.adapter.outbound.gateway.pivot

import com.duluin.ftth.billing.application.port.outbound.InquiryResult
import com.duluin.ftth.billing.application.port.outbound.InquiryStatus
import com.duluin.ftth.billing.application.port.outbound.PivotSubMerchantPort
import com.duluin.ftth.billing.application.port.outbound.SubMerchantCreateRequest
import com.duluin.ftth.billing.application.port.outbound.SubMerchantResult
import com.duluin.ftth.billing.domain.model.PivotMasterContext
import com.duluin.ftth.billing.domain.model.SubAccountKycStatus
import com.duluin.ftth.billing.domain.model.SubAccountStatus
import com.duluin.ftth.common.domain.error.ConflictException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * Adapter port sub-merchant Pivot (`/v1/sub-merchants`, `/v1/inquiry-account`) di atas [PivotApiClient].
 * Menyembunyikan bentuk JSON Pivot: create/fetch mengembalikan [SubMerchantResult] yang sudah
 * dipetakan ke enum domain, inquiry mengembalikan [InquiryResult].
 *
 * SEMUA panggilan memakai kredensial akun MASTER platform (dibangun dari [PivotMasterContext]).
 */
@Component
class PivotSubMerchantGateway(
    private val apiClient: PivotApiClient,
) : PivotSubMerchantPort {

    override fun create(master: PivotMasterContext, request: SubMerchantCreateRequest): SubMerchantResult {
        val body = buildMap<String, Any> {
            put("subAccountType", request.type.name)
            put("shortName", request.shortName)
            put("name", request.name)
            put("website", request.website)
            put("logo", request.logo)
            put("merchantEmail", request.merchantEmail)
            put("merchantPhone", request.merchantPhone)
            put("businessCountry", request.businessCountry)
            put("businessType", request.businessType)
            put("businessStructure", request.businessStructure)
            put("parentIndustry", request.parentIndustry)
            put("childIndustry", request.childIndustry)
            put("mcc", request.mcc)
            put("countryOfEntity", request.countryOfEntity)
            put("digitalStatus", request.digitalStatus)
            put("picName", request.picName)
            put("picEmail", request.picEmail)
            put("picPhone", request.picPhone)
            put("address", request.address)
            put("districtId", request.districtId)
            put("postCode", request.postCode)
            if (!request.bankChannelCode.isNullOrBlank() && !request.bankAccountNumber.isNullOrBlank()) {
                put("bankAccount", mapOf("accountNumber" to request.bankAccountNumber, "channelCode" to request.bankChannelCode))
            }
        }
        return apiClient.post("/v1/sub-merchants", body, master.credentials()).toSubMerchant()
    }

    override fun fetch(master: PivotMasterContext, subMerchantUuid: String): SubMerchantResult =
        apiClient.get("/v1/sub-merchants/$subMerchantUuid", master.credentials()).toSubMerchant()

    override fun inquiryAccount(
        master: PivotMasterContext,
        subMerchantId: String,
        channelCode: String,
        accountNumber: String,
        accountName: String,
    ): InquiryResult {
        val data = apiClient
            .post(
                "/v1/inquiry-account",
                inquiryBody(channelCode, accountNumber, accountName),
                master.credentials(),
                subMerchantId = subMerchantId,
            )
            .dataOrRoot()
        return data.toInquiry()
    }

    override fun assignUser(master: PivotMasterContext, subMerchantId: String, email: String, name: String) {
        apiClient.post(
            "/v1/sub-merchants/admin",
            mapOf("email" to email, "name" to name),
            master.credentials(),
            subMerchantId = subMerchantId,
        )
    }

    override fun resendInvitation(master: PivotMasterContext, subMerchantId: String, email: String) {
        apiClient.post(
            "/v1/sub-merchants/users/resend-invitation",
            mapOf("email" to email),
            master.credentials(),
            subMerchantId = subMerchantId,
        )
    }

    /**
     * Body `POST /v1/inquiry-account` — BERSARANG. Bentuk pipih (`accountNumber` di akar) ditolak
     * Pivot 400 `field_required` dengan pesan berlubang "Make sure  value is fulfilled".
     */
    internal fun inquiryBody(channelCode: String, accountNumber: String, accountName: String): Map<String, Any> =
        mapOf(
            "channelCode" to channelCode,
            "channelInformation" to mapOf("accountNumber" to accountNumber, "accountName" to accountName),
        )

    /** Baca `data.uuid` + `data.inquiryResult.{status,detail}`; toleran bila Pivot memakai nama lain untuk id. */
    internal fun JsonNode.toInquiry(): InquiryResult {
        val inquiryId = textOrNull("uuid") ?: textOrNull("inquiryId") ?: textOrNull("id")
            ?: throw ConflictException("Respons inquiry Pivot tak berisi id")
        val result = get("inquiryResult")?.takeIf { !it.isNull }
        return InquiryResult(
            inquiryId = inquiryId,
            status = mapInquiryStatus(result?.textOrNull("status")),
            detail = result?.textOrNull("detail"),
        )
    }

    private fun PivotMasterContext.credentials() = PivotCredentials(merchantId, merchantSecret, sandbox)

    private fun JsonNode.toSubMerchant(): SubMerchantResult {
        val data = dataOrRoot()
        val uuid = data.textOrNull("id") ?: data.textOrNull("subMerchantId") ?: data.textOrNull("uuid")
            ?: throw ConflictException("Respons sub-account Pivot tak berisi id")
        return SubMerchantResult(
            subMerchantUuid = uuid,
            status = mapStatus(data.textOrNull("subAccountStatus")),
            kycStatus = mapKycStatus(data.textOrNull("subAccountKycStatus")),
        )
    }

    private fun JsonNode.dataOrRoot(): JsonNode = get("data")?.takeIf { !it.isNull } ?: this

    private fun JsonNode.textOrNull(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() }

    private companion object {
        /** Petakan `subAccountStatus` Pivot → enum domain; nilai tak dikenal dianggap CREATED (baru dibuat). */
        fun mapStatus(raw: String?): SubAccountStatus = when (raw?.uppercase()) {
            "ACTIVE" -> SubAccountStatus.ACTIVE
            "DEACTIVATED", "INACTIVE", "SUSPENDED" -> SubAccountStatus.DEACTIVATED
            "REJECTED" -> SubAccountStatus.REJECTED
            else -> SubAccountStatus.CREATED
        }

        /**
         * Petakan `inquiryResult.status` Pivot → enum domain. Nilai tak dikenal (termasuk status
         * absen) dianggap PENDING, bukan VALID: lebih baik payout ditahan daripada diloloskan
         * berdasarkan hasil validasi yang tak dimengerti.
         */
        fun mapInquiryStatus(raw: String?): InquiryStatus = when (raw?.uppercase()) {
            "VALID" -> InquiryStatus.VALID
            "WARNING" -> InquiryStatus.WARNING
            "INVALID" -> InquiryStatus.INVALID
            else -> InquiryStatus.PENDING
        }

        /** Petakan `subAccountKycStatus` Pivot → enum domain; nilai tak dikenal dianggap NOT_REQUIRED. */
        fun mapKycStatus(raw: String?): SubAccountKycStatus = when (raw?.uppercase()) {
            "WAITING_FOR_DOCUMENT", "WAITING_DOCUMENT" -> SubAccountKycStatus.WAITING_FOR_DOCUMENT
            "IN_REVIEW", "REVIEW", "PENDING" -> SubAccountKycStatus.IN_REVIEW
            "APPROVED", "VERIFIED" -> SubAccountKycStatus.APPROVED
            "REJECTED" -> SubAccountKycStatus.REJECTED
            else -> SubAccountKycStatus.NOT_REQUIRED
        }
    }
}
