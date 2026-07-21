package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.monitoring.application.port.outbound.AlarmRepository
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRuleRepository
import com.duluin.ftth.monitoring.domain.model.Alarm
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.AlarmRule
import com.duluin.ftth.monitoring.domain.model.AlarmSeverity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Mengubah bacaan mentah menjadi alarm.
 *
 * Dua sifat yang menentukan apakah sistem alarm berguna atau justru diabaikan
 * orang:
 *
 * - **Tidak membanjiri.** Satu entitas hanya punya satu alarm terbuka per jenis.
 *   Kondisi yang berulang memperbarui alarm yang sama, bukan menambah baris.
 * - **Menutup sendiri.** Kondisi yang pulih menutup alarmnya otomatis. Alarm yang
 *   harus ditutup manual akan menumpuk sampai tidak ada yang membacanya lagi.
 */
@Service
@Transactional
class AlarmEngine(
    private val alarmRepository: AlarmRepository,
    private val alarmRuleRepository: AlarmRuleRepository,
) {
    /**
     * Menilai satu pengamatan dan menaikkan, memperbarui, atau menutup alarmnya.
     *
     * @param value nilai terukur untuk alarm berambang (redaman); `null` untuk
     *        alarm biner seperti LOS.
     * @param conditionPresent apakah kondisi abnormalnya sedang terjadi.
     */
    fun evaluate(
        tenantId: UUID,
        kind: AlarmKind,
        entityId: UUID,
        entityLabel: String,
        conditionPresent: Boolean,
        value: Double? = null,
        messageBuilder: (AlarmSeverity) -> String = { "${kind.description} pada $entityLabel" },
        at: Instant = Instant.now(),
    ): Alarm? {
        val rule = ruleFor(tenantId, kind)
        if (!rule.enabled) return null

        val existing = alarmRepository.findOpen(kind, entityId)

        // Kondisi pulih: tutup alarm yang masih terbuka, kalau ada.
        if (!conditionPresent) {
            existing?.let {
                it.clear(at)
                alarmRepository.save(it)
            }
            return null
        }

        val severity = when {
            value != null -> rule.evaluate(value) ?: run {
                // Nilainya kembali normal menurut ambang, jadi ini juga pemulihan.
                existing?.let {
                    it.clear(at)
                    alarmRepository.save(it)
                }
                return null
            }
            else -> kind.defaultSeverity
        }

        return if (existing != null) {
            existing.reassert(severity, messageBuilder(severity), value, at)
            alarmRepository.save(existing)
        } else {
            alarmRepository.save(
                Alarm.raise(
                    tenantId = tenantId,
                    kind = kind,
                    entityId = entityId,
                    entityLabel = entityLabel,
                    severity = severity,
                    message = messageBuilder(severity),
                    measuredValue = value,
                    at = at,
                ),
            )
        }
    }

    /**
     * Menutup alarm terbuka untuk entitas yang tidak lagi terpantau, mis. ONU yang
     * dibongkar. Tanpa ini, alarmnya menggantung selamanya karena tidak akan
     * pernah ada bacaan baru yang menutupnya.
     */
    fun clearFor(kind: AlarmKind, entityId: UUID, at: Instant = Instant.now()) {
        alarmRepository.findOpen(kind, entityId)?.let {
            it.clear(at)
            alarmRepository.save(it)
        }
    }

    /**
     * Aturan tenant bila ada, kalau tidak nilai bawaan jenis alarmnya. Tenant baru
     * dengan tabel aturan kosong tetap terpantau penuh sejak menit pertama.
     */
    private fun ruleFor(tenantId: UUID, kind: AlarmKind): AlarmRule =
        alarmRuleRepository.findByKind(kind) ?: AlarmRule.defaultFor(tenantId, kind)
}
