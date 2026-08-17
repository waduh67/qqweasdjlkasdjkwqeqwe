package com.duluin.ftth.bng.application.port.inbound

import com.duluin.ftth.bng.domain.model.AuthType
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

    /** Membuat akun jaringan untuk sebuah langganan; satu langganan maksimal satu akun. */
    fun provision(command: ProvisionAccessCommand): SubscriberAccessView

    /** Mengganti paket dan/atau BRAS yang menaungi akun. */
    fun updateAssignment(id: UUID, command: UpdateAccessCommand): SubscriberAccessView

    /** Mengganti password PPPoE. */
    fun resetSecret(id: UUID, command: ResetSecretCommand): SubscriberAccessView

    fun delete(id: UUID)
}

/**
 * [username] identitas login untuk PPPoE/Hotspot (null/kosong → di-generate server-side dari
 * kode pelanggan), atau MAC untuk DHCP/Static (WAJIB diisi, dinormalkan di domain). [secret]
 * password PPPoE/Hotspot (null/kosong → di-generate acak); diabaikan untuk tipe berbasis MAC
 * (MAC jadi password). [framedIp] hanya dipakai DHCP/Static (reservasi Framed-IP-Address; wajib STATIC).
 *
 * [planId] null → diwarisi dari paket langganan. Inilah yang benar hampir selalu: paket langganan
 * adalah yang ditagih, jadi itu pula yang harus dieksekusi jaringan. Tetap boleh diisi berbeda
 * (mis. akses cadangan atau kesepakatan khusus), tapi harus disengaja — bukan konsekuensi
 * dropdown yang lupa diubah.
 */
data class ProvisionAccessCommand(
    val subscriptionId: UUID,
    val username: String?,
    val secret: String?,
    val planId: UUID?,
    val nasId: UUID?,
    val authType: AuthType = AuthType.PPPOE,
    val framedIp: String? = null,
)

data class UpdateAccessCommand(
    val planId: UUID,
    val nasId: UUID?,
)

data class ResetSecretCommand(
    val secret: String,
)
