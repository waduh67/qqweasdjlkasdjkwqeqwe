package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.monitoring.application.port.inbound.AlarmRuleView
import com.duluin.ftth.monitoring.application.port.inbound.ManageAlarmRuleUseCase
import com.duluin.ftth.monitoring.application.port.inbound.UpdateAlarmRuleCommand
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Ambang alarm per tenant.
 *
 * Melihatnya cukup dengan izin baca alarm — orang NOC perlu tahu di angka berapa
 * sistem mulai berteriak untuk menilai alarm yang dibacanya. Mengubahnya izin
 * tersendiri: menggeser ambang menyentuh seluruh pelanggan sekaligus dan bisa
 * membungkam pemantauan tanpa jejak yang kasatmata, jadi bukan wewenang siapa pun
 * yang kebetulan boleh meng-acknowledge alarm.
 */
@RestController
@RequestMapping("/api/monitoring/alarm-rules")
@Tag(name = "Monitoring — Alarm")
@SecurityRequirement(name = "bearer-jwt")
class AlarmRuleController(
    private val useCase: ManageAlarmRuleUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('monitoring.alarm.view')")
    @Operation(summary = "Ambang semua jenis alarm (termasuk yang masih memakai bawaan)")
    fun list(): List<AlarmRuleView> = useCase.list()

    @PutMapping("/{kind}")
    @PreAuthorize("@authz.can('monitoring.threshold.manage')")
    @Operation(summary = "Setel ambang satu jenis alarm")
    fun update(@PathVariable kind: AlarmKind, @Valid @RequestBody request: AlarmRuleRequest): AlarmRuleView =
        useCase.update(
            kind,
            UpdateAlarmRuleCommand(
                enabled = request.enabled,
                warningThreshold = request.warningThreshold,
                criticalThreshold = request.criticalThreshold,
            ),
        )

    @DeleteMapping("/{kind}")
    @PreAuthorize("@authz.can('monitoring.threshold.manage')")
    @Operation(summary = "Kembalikan satu jenis alarm ke ambang bawaan")
    fun reset(@PathVariable kind: AlarmKind): AlarmRuleView = useCase.resetToDefault(kind)
}

data class AlarmRuleRequest(
    @field:NotNull val enabled: Boolean,
    /** `null` berarti tingkat itu tak dipakai — mis. hanya mau alarm kritis saja. */
    val warningThreshold: Double? = null,
    val criticalThreshold: Double? = null,
)
