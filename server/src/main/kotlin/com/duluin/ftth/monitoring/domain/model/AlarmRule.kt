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

    fun update(enabled: Boolean, warningThreshold: Double?, criticalThreshold: Double?, sustainSeconds: Int) {
        validate(kind, warningThreshold, criticalThreshold, sustainSeconds)
        this.enabled = enabled
        this.warningThreshold = warningThreshold
        this.criticalThreshold = criticalThreshold
        this.sustainSeconds = sustainSeconds
    }

    /**
     * Menentukan tingkat keparahan dari nilai terukur.
     *
     * Arah perbandingannya bergantung jenis alarm: untuk redaman lemah, makin
     * KECIL makin buruk; untuk redaman terlalu kuat, makin BESAR makin buruk.
     * `null` berarti kondisinya normal.
     */
    fun evaluate(value: Double): AlarmSeverity? {
        val warning = warningThreshold
        val critical = criticalThreshold
        return when (kind) {
            AlarmKind.ONU_LOW_RX -> when {
                critical != null && value <= critical -> AlarmSeverity.CRITICAL
                warning != null && value <= warning -> AlarmSeverity.WARNING
                else -> null
            }
            AlarmKind.ONU_HIGH_RX -> when {
                critical != null && value >= critical -> AlarmSeverity.CRITICAL
                warning != null && value >= warning -> AlarmSeverity.WARNING
                else -> null
            }
            // Jenis lain bersifat biner (terjadi / tidak), bukan berambang.
            else -> kind.defaultSeverity
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
            validate(kind, warningThreshold, criticalThreshold, sustainSeconds)
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
         * Ambang kritis yang lebih longgar daripada ambang peringatan berarti
         * alarm kritis tidak akan pernah terpicu — kesalahan setelan yang senyap,
         * jadi ditolak di sini.
         */
        private fun validate(kind: AlarmKind, warning: Double?, critical: Double?, sustainSeconds: Int) {
            if (sustainSeconds !in 0..86_400) {
                throw ValidationException("Durasi tahan harus 0-86400 detik")
            }
            if (warning == null || critical == null) return
            val inconsistent = when (kind) {
                AlarmKind.ONU_LOW_RX -> critical > warning
                AlarmKind.ONU_HIGH_RX -> critical < warning
                else -> false
            }
            if (inconsistent) {
                throw ValidationException(
                    "Ambang kritis ($critical) tidak konsisten dengan ambang peringatan ($warning) untuk $kind",
                )
            }
        }
    }
}
