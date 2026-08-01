package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.workorder.application.port.inbound.DegradingOnuSignal
import com.duluin.ftth.workorder.application.port.inbound.RaisePreventiveMaintenanceUseCase
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderPriority
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menerjemahkan sinyal degradasi optik menjadi work order preventif.
 *
 * ONU adalah fakta jaringan; pelanggan yang perlu dikunjungi adalah urusan module
 * customer — jadi ONU dipetakan ke pelanggannya lewat kontrak [CustomerApi], bukan
 * dengan menembus tabelnya. WO yang lahir di sini dibuat sistem: `createdBy = null`,
 * karena memang tak ada pengguna yang mengkliknya.
 */
/*
 * REQUIRES_NEW karena dipanggil dari listener AFTER_COMMIT (lihat
 * [PreventiveMaintenanceListener]): saat itu transaksi penerbit sudah selesai
 * commit tapi sinkronisasinya masih aktif, sehingga REQUIRED justru akan ikut
 * transaksi mati itu dan INSERT-nya tak pernah ter-commit — persis pola
 * IncidentReconciler.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class PreventiveMaintenanceService(
    private val repository: WorkOrderRepository,
    private val customerApi: CustomerApi,
) : RaisePreventiveMaintenanceUseCase {

    override fun raiseForDegradingOnu(signal: DegradingOnuSignal): UUID? {
        val customerId = customerApi.placementsForOnus(setOf(signal.onuId)).firstOrNull()?.customerId
            // ONU tak terpetakan ke pelanggan (perangkat liar) — tak ada yang dikunjungi.
            ?: return null

        // Idempoten: satu pelanggan cukup satu kunjungan preventif terbuka, meski
        // pemindaian berulang terus menandainya memburuk.
        if (repository.existsOpenPreventiveForCustomer(customerId)) return null

        val customerName = customerApi.findCustomer(customerId)?.name
        val workOrder = WorkOrder.open(
            tenantId = TenantContext.tenantId(),
            type = WorkOrderType.PREVENTIVE,
            title = "Preventif: redaman ONU memburuk" + (customerName?.let { " — $it" } ?: ""),
            description = describe(signal),
            // Belum ada gangguan; tinggi agar dijadwalkan sebelum berkembang jadi insiden.
            priority = WorkOrderPriority.HIGH,
            customerId = customerId,
            incidentId = null,
            areaId = null,
            scheduledAt = null,
            assignees = emptySet(),
            createdBy = null,
        )
        return repository.save(workOrder).id
    }

    private fun describe(signal: DegradingOnuSignal): String = buildString {
        append("Pemeliharaan prediktif: redaman ONU memburuk ")
        append("${signal.trendDbPerDay} dB/hari")
        signal.averageRxPowerDbm?.let { append(" (rata-rata $it dBm") }
        signal.minRxPowerDbm?.let { append(if (signal.averageRxPowerDbm != null) ", terendah $it dBm" else " (terendah $it dBm") }
        if (signal.averageRxPowerDbm != null || signal.minRxPowerDbm != null) append(")")
        append(" atas ${signal.samples} sampel. ")
        append("Periksa konektor & serat sebelum layanan pelanggan terganggu.")
    }
}
