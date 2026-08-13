package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.monitoring.application.port.inbound.AlarmRuleView
import com.duluin.ftth.monitoring.application.port.inbound.ManageAlarmRuleUseCase
import com.duluin.ftth.monitoring.application.port.inbound.UpdateAlarmRuleCommand
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRepository
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRuleRepository
import com.duluin.ftth.monitoring.domain.model.Alarm
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.AlarmRule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Sisi operator dari ambang alarm.
 *
 * Dua hal yang membedakannya dari sekadar CRUD:
 *
 * 1. **Daftarnya lengkap, bukan isi tabel.** Jenis yang belum pernah disetel tetap
 *    muncul dengan nilai bawaannya, karena pemantauannya memang sudah berjalan
 *    ([AlarmEngine] jatuh ke `AlarmRule.defaultFor`). Menampilkan tabel apa adanya
 *    akan bilang "belum ada aturan" pada sistem yang sebenarnya sedang memantau.
 * 2. **Perubahan langsung berlaku pada alarm yang sudah terbuka.** Operator yang
 *    melonggarkan ambang melakukannya justru karena melihat alarm yang menurutnya
 *    berlebihan; kalau alarm itu baru berubah setelah siklus polling berikutnya —
 *    atau tak pernah, untuk jenis yang dimatikan — layar dan peta akan tetap merah
 *    dan orang menyimpulkan setelannya tidak berfungsi.
 */
@Service
@Transactional(readOnly = true)
class AlarmRuleService(
    private val ruleRepository: AlarmRuleRepository,
    private val alarmRepository: AlarmRepository,
    private val auditor: AuditRecorder,
) : ManageAlarmRuleUseCase {

    override fun list(): List<AlarmRuleView> {
        val tenantId = TenantContext.tenantId()
        val configured = ruleRepository.findAll().associateBy { it.kind }
        val openCounts = alarmRepository.findAllOpen().groupingBy { it.kind }.eachCount()
        return AlarmKind.entries.map { kind ->
            (configured[kind] ?: AlarmRule.defaultFor(tenantId, kind)).toView(openCounts[kind] ?: 0)
        }
    }

    @Transactional
    override fun update(kind: AlarmKind, command: UpdateAlarmRuleCommand): AlarmRuleView {
        val existing = ruleRepository.findByKind(kind)
        val rule = existing?.apply {
            // Durasi tahan tidak ikut disetel dari layar; pertahankan apa adanya.
            update(command.enabled, command.warningThreshold, command.criticalThreshold, sustainSeconds)
        } ?: AlarmRule.create(
            tenantId = TenantContext.tenantId(),
            kind = kind,
            enabled = command.enabled,
            warningThreshold = command.warningThreshold,
            criticalThreshold = command.criticalThreshold,
        )
        val saved = ruleRepository.save(rule)
        val affected = reapply(saved)
        auditor.record(
            action = "monitoring.alarm_rule.updated",
            entityType = "AlarmRule",
            entityId = saved.id,
            tenantId = saved.tenantId,
            detail = mapOf(
                "kind" to kind.name,
                "enabled" to saved.enabled,
                "warningThreshold" to saved.warningThreshold,
                "criticalThreshold" to saved.criticalThreshold,
                "reassessedAlarms" to affected,
            ),
        )
        return saved.toView(openCountOf(kind))
    }

    @Transactional
    override fun resetToDefault(kind: AlarmKind): AlarmRuleView {
        val tenantId = TenantContext.tenantId()
        val existing = ruleRepository.findByKind(kind)
        val default = AlarmRule.defaultFor(tenantId, kind)
        if (existing != null) {
            ruleRepository.deleteByKind(kind)
            val affected = reapply(default)
            auditor.record(
                action = "monitoring.alarm_rule.reset",
                entityType = "AlarmRule",
                entityId = existing.id,
                tenantId = existing.tenantId,
                detail = mapOf("kind" to kind.name, "reassessedAlarms" to affected),
            )
        }
        return default.toView(openCountOf(kind))
    }

    /**
     * Menerapkan aturan yang baru pada alarm jenis ini yang masih terbuka.
     *
     * Yang dimatikan ditutup semua — kalau tidak, alarmnya menggantung selamanya:
     * tak ada lagi evaluasi yang akan menyentuhnya. Yang masih hidup dinilai ulang
     * dari angka terakhir yang terukur: yang kini normal ditutup, sisanya turun/naik
     * kelas mengikuti ambang baru. Alarm biner (LOS, OLT tak terjangkau) tak punya
     * angka untuk dinilai, jadi dibiarkan — hanya kondisi nyata yang boleh menutupnya.
     *
     * @return jumlah alarm yang berubah, untuk dicatat ke audit.
     */
    private fun reapply(rule: AlarmRule, at: Instant = Instant.now()): Int {
        var changed = 0
        alarmRepository.findAllOpenByKind(rule.kind).forEach { alarm ->
            if (reapplyTo(alarm, rule, at)) {
                alarmRepository.save(alarm)
                changed++
            }
        }
        return changed
    }

    /** @return `true` bila alarmnya benar-benar berubah dan perlu disimpan. */
    private fun reapplyTo(alarm: Alarm, rule: AlarmRule, at: Instant): Boolean {
        if (!rule.enabled) {
            alarm.clear(at)
            return true
        }
        if (rule.kind.thresholdDirection == null) return false
        val value = alarm.measuredValue ?: return false
        val severity = rule.evaluate(value)
        if (severity == null) {
            alarm.clear(at)
            return true
        }
        if (severity == alarm.severity) return false
        alarm.reassess(severity)
        return true
    }

    private fun openCountOf(kind: AlarmKind): Int = alarmRepository.findAllOpenByKind(kind).size

    private fun AlarmRule.toView(openAlarmCount: Int) = AlarmRuleView(
        kind = kind.name,
        description = kind.description,
        entityType = kind.entityType.name,
        enabled = enabled,
        warningThreshold = warningThreshold,
        criticalThreshold = criticalThreshold,
        defaultWarningThreshold = kind.defaultWarningThreshold,
        defaultCriticalThreshold = kind.defaultCriticalThreshold,
        defaultSeverity = kind.defaultSeverity.name,
        direction = kind.thresholdDirection?.name,
        unit = kind.thresholdUnit,
        customised = customised,
        guidance = kind.guidance,
        openAlarmCount = openAlarmCount,
    )
}
