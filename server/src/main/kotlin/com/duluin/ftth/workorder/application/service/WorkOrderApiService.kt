package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.workorder.WorkOrderRef
import com.duluin.ftth.workorder.WorkorderApi
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Implementasi kontrak publik module workorder. Sengaja tipis: menerjemahkan
 * agregat internal ke [WorkOrderRef] datar tanpa membocorkan lifecycle WO.
 */
@Service
@Transactional(readOnly = true)
class WorkOrderApiService(
    private val workOrderRepository: WorkOrderRepository,
) : WorkorderApi {

    override fun openPsbByCustomer(): Map<UUID, WorkOrderRef> =
        workOrderRepository.findOpenByType(WorkOrderType.PSB)
            .filter { it.customerId != null }
            .groupBy { it.customerId!! }
            // Satu pelanggan bisa punya beberapa order terbuka; ambil yang terjadwal paling awal.
            .mapValues { (_, orders) -> orders.minByOrNull { it.scheduledAt ?: Instant.MAX }!!.toRef() }

    private fun WorkOrder.toRef() = WorkOrderRef(
        id = id,
        code = code,
        customerId = customerId!!,
        areaId = areaId,
        scheduledAt = scheduledAt,
    )
}
