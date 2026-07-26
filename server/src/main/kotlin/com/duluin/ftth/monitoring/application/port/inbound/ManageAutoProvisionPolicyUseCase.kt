package com.duluin.ftth.monitoring.application.port.inbound

/**
 * Kebijakan auto-provisioning zero-touch untuk tenant aktif: baca setelan saat
 * ini dan nyalakan/matikan. Saat menyala, ONU liar berkeyakinan HIGH ditautkan
 * otomatis oleh pemindai terjadwal alih-alih menunggu operator menekan "Terima".
 */
interface ManageAutoProvisionPolicyUseCase {

    fun get(): AutoProvisionPolicyView

    fun setEnabled(enabled: Boolean): AutoProvisionPolicyView
}

data class AutoProvisionPolicyView(
    /** Zero-touch aktif? Default `false` untuk tenant yang belum pernah menyetel. */
    val enabled: Boolean,
)
