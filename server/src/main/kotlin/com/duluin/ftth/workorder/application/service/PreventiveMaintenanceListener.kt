package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.integration.OpticalDegradationDetected
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.workorder.application.port.inbound.DegradingOnuSignal
import com.duluin.ftth.workorder.application.port.inbound.RaisePreventiveMaintenanceUseCase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Mendengarkan [OpticalDegradationDetected] dari monitoring dan mengangkat work
 * order preventif untuk ONU yang bersangkutan.
 *
 * Berjalan pada fase AFTER_COMMIT — WO hanya boleh lahir dari sinyal yang benar
 * ter-commit, bukan dari pemindaian yang justru di-rollback. Tenant context
 * dipasang dari event, bukan thread saat ini, karena penerbitnya (pemindai
 * prediktif) berjalan tanpa pengguna. `fallbackExecution = true` agar event yang
 * terbit di luar transaksi tetap diproses. Kegagalan di sini tak boleh
 * menggagalkan operasi penerbit.
 */
@Component
class PreventiveMaintenanceListener(
    private val useCase: RaisePreventiveMaintenanceUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: OpticalDegradationDetected) {
        try {
            TenantContext.runAs(event.tenantId) {
                useCase.raiseForDegradingOnu(
                    DegradingOnuSignal(
                        onuId = event.onuId,
                        trendDbPerDay = event.trendDbPerDay,
                        averageRxPowerDbm = event.averageRxPowerDbm,
                        minRxPowerDbm = event.minRxPowerDbm,
                        samples = event.samples,
                    ),
                )
            }
        } catch (ex: Exception) {
            log.warn("Gagal mengangkat WO preventif untuk ONU {} tenant {}", event.onuId, event.tenantId, ex)
        }
    }
}
