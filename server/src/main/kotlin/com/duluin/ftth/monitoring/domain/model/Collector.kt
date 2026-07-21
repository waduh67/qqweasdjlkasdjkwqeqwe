package com.duluin.ftth.monitoring.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

enum class CollectorStatus {
    ACTIVE,
    /** Sengaja dihentikan sementara, mis. saat pemeliharaan jaringan ISP. */
    PAUSED,
    DISABLED,
}

/**
 * Agent yang berjalan di jaringan ISP dan mem-polling OLT.
 *
 * API key-nya hanya disimpan sebagai hash SHA-256, sama seperti refresh token:
 * kunci mentah ditampilkan sekali saat dibuat lalu tidak bisa dilihat lagi.
 * Bocornya database tidak boleh berarti bocornya akses ke jaringan pelanggan.
 */
class Collector private constructor(
    val id: UUID,
    val tenantId: UUID,
    name: String,
    val apiKeyHash: String,
    val apiKeyHint: String,
    status: CollectorStatus,
    pollIntervalSeconds: Int,
    agentVersion: String?,
    lastSeenAt: Instant?,
    lastCycleSummary: String?,
) {
    var name: String = name
        private set

    var status: CollectorStatus = status
        private set

    var pollIntervalSeconds: Int = pollIntervalSeconds
        private set

    var agentVersion: String? = agentVersion
        private set

    var lastSeenAt: Instant? = lastSeenAt
        private set

    var lastCycleSummary: String? = lastCycleSummary
        private set

    fun update(name: String, pollIntervalSeconds: Int, status: CollectorStatus) {
        this.name = validateName(name)
        this.pollIntervalSeconds = validateInterval(pollIntervalSeconds)
        this.status = status
    }

    /** Dicatat tiap denyut, menjadi dasar deteksi collector yang membisu. */
    fun recordHeartbeat(agentVersion: String, cycleSummary: String?, at: Instant = Instant.now()) {
        this.agentVersion = agentVersion
        this.lastSeenAt = at
        if (cycleSummary != null) this.lastCycleSummary = cycleSummary
    }

    /** Boleh mengirim data? Collector yang dinonaktifkan ditolak di gerbang autentikasi. */
    fun canIngest(): Boolean = status != CollectorStatus.DISABLED

    /**
     * Dianggap membisu bila melewatkan beberapa siklus berturut-turut. Memakai
     * kelipatan interval polling, bukan angka tetap, karena collector dengan
     * interval 1 jam wajar saja diam 45 menit.
     */
    fun isSilent(now: Instant = Instant.now(), missedCycles: Int = 3): Boolean {
        if (status != CollectorStatus.ACTIVE) return false
        val seen = lastSeenAt ?: return false
        return Duration.between(seen, now).seconds > pollIntervalSeconds.toLong() * missedCycles
    }

    companion object {
        const val MIN_POLL_SECONDS = 30
        const val MAX_POLL_SECONDS = 86_400
        private const val API_KEY_BYTES = 32

        /**
         * Membuat collector baru sekaligus API key-nya.
         *
         * Mengembalikan kunci mentah bersama agregatnya karena inilah satu-satunya
         * kesempatan kunci itu ada: setelahnya hanya hash yang tersimpan.
         */
        fun create(
            tenantId: UUID,
            name: String,
            pollIntervalSeconds: Int = 300,
            keyGenerator: () -> String = ::generateApiKey,
        ): NewCollector {
            val apiKey = keyGenerator()
            val collector = Collector(
                id = UuidV7.generate(),
                tenantId = tenantId,
                name = validateName(name),
                apiKeyHash = hashApiKey(apiKey),
                apiKeyHint = apiKey.take(8),
                status = CollectorStatus.ACTIVE,
                pollIntervalSeconds = validateInterval(pollIntervalSeconds),
                agentVersion = null,
                lastSeenAt = null,
                lastCycleSummary = null,
            )
            return NewCollector(collector, apiKey)
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            name: String,
            apiKeyHash: String,
            apiKeyHint: String,
            status: CollectorStatus,
            pollIntervalSeconds: Int,
            agentVersion: String?,
            lastSeenAt: Instant?,
            lastCycleSummary: String?,
        ): Collector = Collector(
            id, tenantId, name, apiKeyHash, apiKeyHint, status,
            pollIntervalSeconds, agentVersion, lastSeenAt, lastCycleSummary,
        )

        /** URL-safe agar aman dipasang di variabel lingkungan dan berkas systemd. */
        fun generateApiKey(): String {
            val bytes = ByteArray(API_KEY_BYTES).also(SecureRandom()::nextBytes)
            return "ftthc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /**
         * SHA-256 polos, bukan bcrypt: kunci ini dicek pada SETIAP request
         * collector, dan entropinya 256 bit dari sumber acak kriptografis —
         * tidak ada yang bisa didapat penyerang dari serangan kamus. Memakai
         * bcrypt di jalur ini justru membuat ingestion tercekik.
         */
        fun hashApiKey(apiKey: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(apiKey.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..150) throw ValidationException("Nama collector harus 2-150 karakter")
            return trimmed
        }

        private fun validateInterval(seconds: Int): Int {
            if (seconds !in MIN_POLL_SECONDS..MAX_POLL_SECONDS) {
                throw ValidationException("Interval polling harus $MIN_POLL_SECONDS-$MAX_POLL_SECONDS detik")
            }
            return seconds
        }
    }
}

/** Collector baru beserta API key mentahnya — satu-satunya saat kunci itu terlihat. */
data class NewCollector(val collector: Collector, val apiKey: String)
