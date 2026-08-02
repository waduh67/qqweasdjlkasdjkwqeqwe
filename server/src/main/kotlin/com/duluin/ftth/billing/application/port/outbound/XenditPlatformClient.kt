package com.duluin.ftth.billing.application.port.outbound

/**
 * Klien Xendit tingkat-PLATFORM (akun MASTER agregator/xenPlatform). Dipakai aksi
 * platform-admin untuk menyiapkan sub-account tenant — BUKAN untuk charge (itu lewat
 * [PaymentGateway] dengan header `for-user-id`). Semua panggilan memakai basic-auth
 * secret key MASTER dari config, jadi tak menyentuh baris tenant/RLS.
 */
interface XenditPlatformClient {

    /**
     * Buat sub-account MANAGED (xenPlatform) atas nama tenant. Balikkan [XenditSubAccount.userId]
     * (`id` respons) yang dipakai sebagai `for-user-id` di charge berikutnya, plus token callback
     * bila Xendit menyertakannya di respons (umumnya tidak — jatuh ke token platform global).
     */
    fun createManagedSubAccount(email: String, businessName: String): XenditSubAccount

    /**
     * Arahkan callback invoice sub-account ke URL webhook per-slug kita
     * (`<callbackBaseUrl>/api/billing/webhooks/{slug}/xendit`) via `POST /callback_urls/invoice`
     * dengan header `for-user-id`. Best-effort: kegagalan tak menganulir sub-account yang sudah jadi.
     */
    fun setInvoiceCallbackUrl(subAccountId: String, callbackUrl: String)
}

/**
 * Hasil pembuatan sub-account. [userId] = `id` akun (header `for-user-id`); [callbackToken] =
 * token verifikasi callback sub-account bila tersedia, else null (resolve() fallback ke token
 * platform global).
 */
data class XenditSubAccount(
    val userId: String,
    val callbackToken: String?,
)
