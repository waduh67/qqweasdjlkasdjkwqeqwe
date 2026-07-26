package com.duluin.ftth.bng.application.port.inbound

import java.util.UUID

/**
 * Kelola identitas jaringan (akun PPPoE) pelanggan. Untuk slice fondasi ini semua
 * operasi bersifat data — belum ada efek jaringan (provisioning/isolir nyata ke
 * BRAS menyusul di slice berikutnya).
 */
interface ManageSubscriberAccessUseCase {

    /** Seluruh akun milik satu pelanggan (bisa >1 bila punya beberapa langganan). */
    fun listForCustomer(customerId: UUID): List<SubscriberAccessView>

    /** Akun untuk satu langganan (0..1). */
    fun listForSubscription(subscriptionId: UUID): List<SubscriberAccessView>

    fun get(id: UUID): SubscriberAccessView

    /** Membuat akun PPPoE untuk sebuah langganan; satu langganan maksimal satu akun. */
    fun provision(command: ProvisionAccessCommand): SubscriberAccessView

    /** Mengganti paket dan/atau BRAS yang menaungi akun. */
    fun updateAssignment(id: UUID, command: UpdateAccessCommand): SubscriberAccessView

    /** Mengganti password PPPoE. */
    fun resetSecret(id: UUID, command: ResetSecretCommand): SubscriberAccessView

    fun delete(id: UUID)
}

data class ProvisionAccessCommand(
    val subscriptionId: UUID,
    val username: String,
    val secret: String,
    val rateProfileId: UUID,
    val nasId: UUID?,
)

data class UpdateAccessCommand(
    val rateProfileId: UUID,
    val nasId: UUID?,
)

data class ResetSecretCommand(
    val secret: String,
)
