package com.duluin.ftth.bng.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Cara pelanggan diautentikasi ke jaringan:
 *  - [PPPOE]/[HOTSPOT]: identitas login (username + password), dial/portal.
 *  - [DHCP]/[STATIC]: identitas berbasis MAC (`use-radius` Mikrotik) — MAC jadi username
 *    DAN password; [STATIC] menambah reservasi `Framed-IP-Address`.
 *
 * Semua tipe memakai grup rate-limit paket yang sama (`plan:{id}`) — atribut
 * Mikrotik-Rate-Limit berlaku lintas-tipe. [macBased] memutuskan skema identitas:
 * MAC global-unik → TAK di-prefix slug tenant (username login yang di-prefix).
 */
enum class AuthType {
    PPPOE,
    HOTSPOT,
    DHCP,
    STATIC,
    ;

    val macBased: Boolean get() = this == DHCP || this == STATIC
}

/**
 * Status identitas jaringan, mencerminkan status langganan tapi hidup terpisah
 * karena ia yang menggerakkan efek jaringan nyata (ganti profil, tendang sesi).
 *
 * [PENDING]: akun sudah dibuat tapi BELUM ditulis ke RADIUS — dibuat saat langganan masih
 * menunggu instalasi (WO PSB belum selesai), sehingga pelanggan tak bisa online duluan.
 * Otorisasi RADIUS baru ditulis saat langganan diaktifkan (lihat
 * [com.duluin.ftth.bng.application.service.SubscriberAccessLifecycle.onActivated]).
 */
enum class AccessStatus { PENDING, ACTIVE, ISOLATED, TERMINATED }

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
 *
 * [framedIp] hanya untuk tipe berbasis MAC yang meminta reservasi IP (wajib [AuthType.STATIC],
 * opsional [AuthType.DHCP]) → ditulis sebagai `radreply Framed-IP-Address`; null untuk
 * PPPoE/Hotspot. Terikat identitas → tak berubah setelah dibuat.
 */
class SubscriberAccess private constructor(
    val id: UUID,
    val tenantId: UUID,
    val subscriptionId: UUID,
    val customerId: UUID,
    /** Identitas login (atau MAC untuk tipe berbasis MAC); tak berubah setelah dibuat. */
    val username: String,
    val authType: AuthType,
    secret: String,
    planId: UUID,
    nasId: UUID?,
    status: AccessStatus,
    fupThrottled: Boolean,
    /** Reservasi IP (Framed-IP-Address) untuk DHCP/STATIC; null bila tak dipakai. */
    val framedIp: String?,
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
        if (authType.macBased) {
            throw ConflictException("Akun berbasis MAC (DHCP/Static) tak punya password untuk direset")
        }
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
        /**
         * Membuat akun; skema identitas ditentukan [authType]:
         *  - PPPoE/Hotspot: [username] login + [secret] password (keduanya divalidasi).
         *  - DHCP/Static: [username] adalah MAC (dinormalkan ke `AA:BB:CC:DD:EE:FF`) yang
         *    dipakai SEKALIGUS sebagai password (konvensi `use-radius`); [secret] diabaikan.
         *    [framedIp] jadi reservasi `Framed-IP-Address` — wajib untuk STATIC, opsional DHCP.
         */
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
            authType: AuthType = AuthType.PPPOE,
            framedIp: String? = null,
        ): SubscriberAccess {
            val identity: String
            val effectiveSecret: String
            val reservedIp: String?
            when (authType) {
                AuthType.PPPOE, AuthType.HOTSPOT -> {
                    identity = validateUsername(username)
                    effectiveSecret = validateSecret(secret)
                    reservedIp = null
                }
                AuthType.DHCP, AuthType.STATIC -> {
                    identity = normalizeMac(username)
                    effectiveSecret = identity
                    reservedIp = validateFramedIp(framedIp, required = authType == AuthType.STATIC)
                }
            }
            return SubscriberAccess(
                id = UuidV7.generate(),
                tenantId = tenantId,
                subscriptionId = subscriptionId,
                customerId = customerId,
                username = identity,
                authType = authType,
                secret = effectiveSecret,
                planId = planId,
                nasId = nasId,
                status = status,
                fupThrottled = false,
                framedIp = reservedIp,
            )
        }

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
            framedIp: String?,
        ): SubscriberAccess = SubscriberAccess(
            id, tenantId, subscriptionId, customerId, username, authType, secret, planId, nasId, status,
            fupThrottled, framedIp,
        )

        private val USERNAME_PATTERN = Regex("^[A-Za-z0-9._@-]{2,64}$")
        private val MAC_HEX_PATTERN = Regex("^[0-9A-F]{12}$")
        private val IPV4_PATTERN =
            Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")

        private fun validateUsername(username: String): String {
            val trimmed = username.trim()
            if (!USERNAME_PATTERN.matches(trimmed)) {
                throw ValidationException("Username 2-64 karakter, hanya huruf/angka dan . _ - @")
            }
            return trimmed
        }

        private fun validateSecret(secret: String): String {
            if (secret.length !in 4..128) throw ValidationException("Password harus 4-128 karakter")
            return secret
        }

        /** Normalkan MAC ke bentuk kanonik `AA:BB:CC:DD:EE:FF` (terima pemisah `:` `-` `.` atau tanpa pemisah). */
        private fun normalizeMac(raw: String): String {
            val hex = raw.trim().uppercase().replace(Regex("[.:-]"), "")
            if (!MAC_HEX_PATTERN.matches(hex)) {
                throw ValidationException("MAC address harus 12 digit heksadesimal (mis. AA:BB:CC:DD:EE:FF)")
            }
            return hex.chunked(2).joinToString(":")
        }

        /** Validasi reservasi IPv4; [required] untuk STATIC (wajib), DHCP boleh kosong (dinamis). */
        private fun validateFramedIp(framedIp: String?, required: Boolean): String? {
            val trimmed = framedIp?.trim()?.takeIf { it.isNotEmpty() }
                ?: return if (required) {
                    throw ValidationException("IP Statis butuh alamat IP yang direservasi (Framed-IP-Address)")
                } else {
                    null
                }
            if (!IPV4_PATTERN.matches(trimmed)) {
                throw ValidationException("Reserved IP harus IPv4 valid, mis. 100.64.0.10")
            }
            return trimmed
        }
    }
}
