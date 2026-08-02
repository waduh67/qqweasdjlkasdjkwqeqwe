package com.duluin.ftth.billing.application.port.inbound

import java.util.UUID

/**
 * Aksi platform-admin: siapkan sub-account Xendit (mode PLATFORM/xenPlatform) untuk sebuah tenant,
 * lalu kunci baris gateway tenant itu ke XENDIT/PLATFORM/aktif. Dipisah dari setelan self-service
 * tenant ([ManagePaymentGatewaySettingsUseCase]) karena butuh kredensial MASTER & izin platform.
 */
interface ProvisionSubAccountUseCase {
    fun provisionXendit(command: ProvisionSubAccountCommand): SubAccountProvisionResult
}

/**
 * [tenantId] tenant sasaran; [email] alamat email sub-account (WAJIB unik di Xendit);
 * [businessName] nama bisnis publik sub-account (kosong = pakai nama tenant).
 */
data class ProvisionSubAccountCommand(
    val tenantId: UUID,
    val email: String,
    val businessName: String?,
)

/** [subAccountId] user_id sub-account; [callbackTokenSet] true bila token sub-account tersimpan. */
data class SubAccountProvisionResult(
    val tenantId: UUID,
    val subAccountId: String,
    val callbackTokenSet: Boolean,
)
