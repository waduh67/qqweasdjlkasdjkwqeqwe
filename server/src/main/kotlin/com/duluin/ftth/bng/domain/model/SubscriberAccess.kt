package com.duluin.ftth.bng.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/** Cara pelanggan diautentikasi ke jaringan; PPPoE dulu, IPoE/STATIC menyusul. */
enum class AuthType { PPPOE }

/**
 * Status identitas jaringan, mencerminkan status langganan tapi hidup terpisah
 * karena kelak ia yang menggerakkan efek jaringan nyata (ganti profil, tendang sesi).
 */
enum class AccessStatus { ACTIVE, ISOLATED, TERMINATED }

/**
 * Identitas jaringan seorang pelanggan — akun PPPoE yang dipakai login internet.
 *
 * Ditaut ke `subscription` (module customer) lewat [subscriptionId] berupa UUID
 * polos tanpa FK, menjaga batas antar-module. [customerId] didenormalisasi agar
 * panel pelanggan bisa menampilkan seluruh akunnya tanpa menembus module customer.
 *
 * [secret] (password PPPoE) dipegang apa adanya di domain; adapter persistence yang
 * mengenkripsi ke DB. Ia tidak pernah dikembalikan lewat API — hanya bisa diisi/reset.
 *
 * [planId] menaut ke paket di modul `catalog` (UUID polos tanpa FK, menjaga batas
 * antar-module). Akun tak menyimpan atribut jaringan sendiri: kecepatan/QoS dibaca live
 * dari paket saat provisioning ke RADIUS, sehingga perubahan paket menyebar tanpa
 * menyentuh akun.
 */
class SubscriberAccess private constructor(
    val id: UUID,
    val tenantId: UUID,
    val subscriptionId: UUID,
    val customerId: UUID,
    /** Identitas login; tak berubah setelah dibuat. */
    val username: String,
    val authType: AuthType,
    secret: String,
    planId: UUID,
    nasId: UUID?,
    status: AccessStatus,
    fupThrottled: Boolean,
) {
    var secret: String = secret
        private set

    var planId: UUID = planId
        private set

    var nasId: UUID? = nasId
        private set

    var status: AccessStatus = status
        private set

    /**
     * Kuota FUP terlampaui → akun kini dipindah ke grup throttle RADIUS. Bendera ini
     * mencegah antre-ganda: penegak FUP hanya me-remap sekali saat pertama melampaui,
     * dan hanya memulihkan sekali saat pemakaian turun/siklus berganti.
     */
    var fupThrottled: Boolean = fupThrottled
        private set

    fun resetSecret(newSecret: String) {
        assertNotTerminated()
        this.secret = validateSecret(newSecret)
    }

    fun assignPlan(planId: UUID) {
        assertNotTerminated()
        this.planId = planId
    }

    fun moveToNas(nasId: UUID?) {
        assertNotTerminated()
        this.nasId = nasId
    }

    /** Idempoten: aman dipanggil ulang oleh listener event langganan. */
    fun activate() {
        assertNotTerminated()
        status = AccessStatus.ACTIVE
    }

    fun isolate() {
        assertNotTerminated()
        status = AccessStatus.ISOLATED
    }

    fun terminate() {
        status = AccessStatus.TERMINATED
    }

    /** Tandai akun ter-throttle FUP (dipindah ke grup FUP). Hanya akun aktif yang di-throttle. */
    fun applyFupThrottle() {
        assertNotTerminated()
        fupThrottled = true
    }

    /**
     * Cabut throttle FUP (dikembalikan ke grup normal). Idempoten — aman dipanggil saat
     * rollover siklus, pemulihan pemakaian, maupun penggantian paket.
     */
    fun clearFupThrottle() {
        fupThrottled = false
    }

    private fun assertNotTerminated() {
        if (status == AccessStatus.TERMINATED) {
            throw ConflictException("Akun jaringan sudah dihentikan dan tidak bisa diubah lagi")
        }
    }

    companion object {
        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            subscriptionId: UUID,
            customerId: UUID,
            username: String,
            secret: String,
            planId: UUID,
            nasId: UUID?,
            status: AccessStatus,
        ): SubscriberAccess = SubscriberAccess(
            id = UuidV7.generate(),
            tenantId = tenantId,
            subscriptionId = subscriptionId,
            customerId = customerId,
            username = validateUsername(username),
            authType = AuthType.PPPOE,
            secret = validateSecret(secret),
            planId = planId,
            nasId = nasId,
            status = status,
            fupThrottled = false,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            subscriptionId: UUID,
            customerId: UUID,
            username: String,
            authType: AuthType,
            secret: String,
            planId: UUID,
            nasId: UUID?,
            status: AccessStatus,
            fupThrottled: Boolean,
        ): SubscriberAccess = SubscriberAccess(
            id, tenantId, subscriptionId, customerId, username, authType, secret, planId, nasId, status, fupThrottled,
        )

        private val USERNAME_PATTERN = Regex("^[A-Za-z0-9._@-]{2,64}$")

        private fun validateUsername(username: String): String {
            val trimmed = username.trim()
            if (!USERNAME_PATTERN.matches(trimmed)) {
                throw ValidationException("Username PPPoE 2-64 karakter, hanya huruf/angka dan . _ - @")
            }
            return trimmed
        }

        private fun validateSecret(secret: String): String {
            if (secret.length !in 4..128) throw ValidationException("Password PPPoE harus 4-128 karakter")
            return secret
        }
    }
}
