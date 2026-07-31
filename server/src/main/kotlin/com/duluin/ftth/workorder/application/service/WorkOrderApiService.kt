package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.workorder.RaisePsbCommand
import com.duluin.ftth.workorder.WorkOrderRef
import com.duluin.ftth.workorder.WorkorderApi
import com.duluin.ftth.workorder.application.port.inbound.ManageWorkOrderUseCase
import com.duluin.ftth.workorder.application.port.inbound.SaveWorkOrderCommand
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderPriority
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
    private val manageWorkOrder: ManageWorkOrderUseCase,
) : WorkorderApi {

    override fun openPsbByCustomer(): Map<UUID, WorkOrderRef> =
        workOrderRepository.findOpenByType(WorkOrderType.PSB)
            .filter { it.customerId != null }
            .groupBy { it.customerId!! }
            // Satu pelanggan bisa punya beberapa order terbuka; ambil yang terjadwal paling awal.
            .mapValues { (_, orders) -> orders.minByOrNull { it.scheduledAt ?: Instant.MAX }!!.toRef() }

    @Transactional
    override fun raisePsb(command: RaisePsbCommand): WorkOrderRef {
        val view = manageWorkOrder.create(
            SaveWorkOrderCommand(
                type = WorkOrderType.PSB,
                title = command.title,
                description = command.description,
                priority = WorkOrderPriority.NORMAL,
                customerId = command.customerId,
                subscriptionId = command.subscriptionId,
                incidentId = null,
                areaId = command.areaId,
                scheduledAt = command.scheduledAt,
                assignedTo = command.assignedTo,
            ),
        )
        return WorkOrderRef(
            id = view.id,
            code = view.code,
            customerId = command.customerId,
            areaId = view.areaId,
            scheduledAt = view.scheduledAt,
        )
    }

    private fun WorkOrder.toRef() = WorkOrderRef(
        id = id,
        code = code,
        customerId = customerId!!,
        areaId = areaId,
        scheduledAt = scheduledAt,
    )
}
