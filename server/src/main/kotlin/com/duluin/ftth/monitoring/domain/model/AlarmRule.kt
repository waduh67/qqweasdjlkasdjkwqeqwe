package com.duluin.ftth.monitoring.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Penyetelan sebuah [AlarmKind] untuk satu tenant.
 *
 * Aturan yang belum pernah disetel tidak perlu ada barisnya — [defaultFor]
 * menghasilkan aturan dari nilai bawaan jenis alarmnya. Artinya tenant baru
 * langsung terpantau, dan tabelnya hanya berisi hal-hal yang memang sengaja
 * diubah operator.
 */
class AlarmRule private constructor(
    val id: UUID,
    val tenantId: UUID,
    val kind: AlarmKind,
    enabled: Boolean,
    warningThreshold: Double?,
    criticalThreshold: Double?,
    sustainSeconds: Int,
) {
    var enabled: Boolean = enabled
        private set

    var warningThreshold: Double? = warningThreshold
        private set

    var criticalThreshold: Double? = criticalThreshold
        private set

    var sustainSeconds: Int = sustainSeconds
        private set

    /** Apakah setelan ini sudah menyimpang dari bawaan jenisnya — untuk ditandai di layar. */
    val customised: Boolean
        get() = !enabled ||
            warningThreshold != kind.defaultWarningThreshold ||
            criticalThreshold != kind.defaultCriticalThreshold ||
            sustainSeconds != kind.defaultSustainSeconds

    fun update(enabled: Boolean, warningThreshold: Double?, criticalThreshold: Double?, sustainSeconds: Int) {
        validate(kind, enabled, warningThreshold, criticalThreshold, sustainSeconds)
        this.enabled = enabled
        this.warningThreshold = warningThreshold
        this.criticalThreshold = criticalThreshold
        this.sustainSeconds = sustainSeconds
    }

    /**
     * Menentukan tingkat keparahan dari nilai terukur.
     *
     * Arah perbandingannya diambil dari [AlarmKind.thresholdDirection] — untuk
     * redaman lemah makin KECIL makin buruk, untuk redaman terlalu kuat makin BESAR
     * makin buruk. `null` berarti kondisinya normal.
     */
    fun evaluate(value: Double): AlarmSeverity? {
        // Jenis tak berambang bersifat biner (terjadi / tidak), tak ada yang dibandingkan.
        val direction = kind.thresholdDirection ?: return kind.defaultSeverity
        fun breached(threshold: Double?) = threshold != null && when (direction) {
            AlarmThresholdDirection.LOWER_IS_WORSE -> value <= threshold
            AlarmThresholdDirection.HIGHER_IS_WORSE -> value >= threshold
        }
        return when {
            breached(criticalThreshold) -> AlarmSeverity.CRITICAL
            breached(warningThreshold) -> AlarmSeverity.WARNING
            else -> null
        }
    }

    companion object {
        fun create(
            tenantId: UUID,
            kind: AlarmKind,
            enabled: Boolean = true,
            warningThreshold: Double? = kind.defaultWarningThreshold,
            criticalThreshold: Double? = kind.defaultCriticalThreshold,
            sustainSeconds: Int = kind.defaultSustainSeconds,
        ): AlarmRule {
            validate(kind, enabled, warningThreshold, criticalThreshold, sustainSeconds)
            return AlarmRule(UuidV7.generate(), tenantId, kind, enabled, warningThreshold, criticalThreshold, sustainSeconds)
        }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            kind: AlarmKind,
            enabled: Boolean,
            warningThreshold: Double?,
            criticalThreshold: Double?,
            sustainSeconds: Int,
        ): AlarmRule = AlarmRule(id, tenantId, kind, enabled, warningThreshold, criticalThreshold, sustainSeconds)

        /** Aturan bawaan untuk jenis yang belum pernah disetel tenant ini. */
        fun defaultFor(tenantId: UUID, kind: AlarmKind): AlarmRule = AlarmRule(
            id = UuidV7.generate(),
            tenantId = tenantId,
            kind = kind,
            enabled = true,
            warningThreshold = kind.defaultWarningThreshold,
            criticalThreshold = kind.defaultCriticalThreshold,
            sustainSeconds = kind.defaultSustainSeconds,
        )

        /**
         * Menolak setelan yang mematikan alarm tanpa terlihat mematikannya.
         *
         * Dua jebakan yang sama-sama senyap: (1) ambang kritis yang lebih longgar
         * daripada ambang peringatan — kritisnya tak akan pernah terpicu; (2) jenis
         * berambang yang dinyalakan tapi kedua ambangnya dikosongkan — mesin tak
         * punya apa pun untuk dibandingkan, jadi layarnya bilang "aktif" sementara
         * kenyataannya buta. Keduanya baru ketahuan saat gangguan sungguhan lewat
         * tanpa alarm, maka dijegal di sini.
         */
        private fun validate(
            kind: AlarmKind,
            enabled: Boolean,
            warning: Double?,
            critical: Double?,
            sustainSeconds: Int,
        ) {
            if (sustainSeconds !in 0..86_400) {
                throw ValidationException("Durasi tahan harus 0-86400 detik")
            }
            val direction = kind.thresholdDirection ?: return
            if (enabled && warning == null && critical == null) {
                throw ValidationException("$kind butuh minimal satu ambang; kosongkan lewat tombol nonaktif")
            }
            if (warning == null || critical == null) return
            val inconsistent = when (direction) {
                AlarmThresholdDirection.LOWER_IS_WORSE -> critical > warning
                AlarmThresholdDirection.HIGHER_IS_WORSE -> critical < warning
            }
            if (inconsistent) {
                throw ValidationException(
                    "Ambang kritis ($critical) tidak konsisten dengan ambang peringatan ($warning) untuk $kind",
                )
            }
        }
    }
}
