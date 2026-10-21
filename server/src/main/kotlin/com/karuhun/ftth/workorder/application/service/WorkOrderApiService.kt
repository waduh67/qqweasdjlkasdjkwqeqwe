package com.karuhun.ftth.workorder.application.service

import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.inventory.InventoryTenantCutoverApi
import com.karuhun.ftth.inventory.WarehouseWorkflow
import com.karuhun.ftth.workorder.FieldOpsReport
import com.karuhun.ftth.workorder.RaisePsbCommand
import com.karuhun.ftth.workorder.RaiseRepairCommand
import com.karuhun.ftth.workorder.TechnicianProductivity
import com.karuhun.ftth.workorder.WorkOrderRef
import com.karuhun.ftth.workorder.WorkOrderAssignmentRef
import com.karuhun.ftth.workorder.WorkorderApi
import com.karuhun.ftth.workorder.application.port.inbound.ManageWorkOrderUseCase
import com.karuhun.ftth.workorder.application.port.inbound.ReferenceWorkSource
import com.karuhun.ftth.workorder.application.port.inbound.SaveWorkOrderCommand
import com.karuhun.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.karuhun.ftth.workorder.domain.model.WorkOrder
import com.karuhun.ftth.workorder.domain.model.WorkOrderPriority
import com.karuhun.ftth.workorder.domain.model.WorkOrderType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    private val cutovers: InventoryTenantCutoverApi,
    private val intakes: ReferenceWorkIntakeService,
    private val referenceOrders: com.karuhun.ftth.workorder.adapter.outbound.persistence.ReferenceWorkOrderStore,
    private val customers: com.karuhun.ftth.customer.CustomerApi,
    private val iam: com.karuhun.ftth.iam.IamApi,
) : WorkorderApi {

    override fun assignment(workOrderId: UUID, technicianId: UUID): WorkOrderAssignmentRef? {
        val reference = referenceOrders.find(workOrderId)
        val workOrder = workOrderRepository.findById(workOrderId) ?: return null
        return if (workOrder.isAssignedTo(technicianId)) {
            WorkOrderAssignmentRef(workOrder.tenantId, workOrder.id, workOrder.orderId, technicianId, workOrder.status.open, workOrder.areaId, reference?.assignmentGeneration)
        } else {
            null
        }
    }

    override fun scheduledAt(workOrderId: UUID): Instant? = workOrderRepository.findById(workOrderId)?.scheduledAt

    override fun visitContext(workOrderId: UUID, lock: Boolean): com.karuhun.ftth.workorder.WorkOrderVisitContext? {
        val reference = referenceOrders.find(workOrderId, lock)
        val workOrder = workOrderRepository.findById(workOrderId) ?: return null
        return com.karuhun.ftth.workorder.WorkOrderVisitContext(workOrder.tenantId, workOrder.code, workOrder.title,
            workOrder.customerId?.let(customers::findCustomer)?.name, workOrder.areaId,
            workOrder.areaId?.let { iam.areasByIds(setOf(it)).singleOrNull()?.name }, workOrder.scheduledAt,
            workOrder.status.open, workOrder.assignees, reference?.assignmentGeneration)
    }

    override fun openPsbByCustomer(): Map<UUID, WorkOrderRef> =
        workOrderRepository.findOpenByType(WorkOrderType.PSB)
            .filter { it.customerId != null }
            .groupBy { it.customerId!! }
            // Satu pelanggan bisa punya beberapa order terbuka; ambil yang terjadwal paling awal.
            .mapValues { (_, orders) -> orders.minByOrNull { it.scheduledAt ?: Instant.MAX }!!.toRef() }

    @Transactional
    override fun raisePsb(command: RaisePsbCommand): WorkOrderRef {
        if (cutovers.read().workflow == WarehouseWorkflow.REFERENCE) {
            if (command.assignees.isNotEmpty()) throw ValidationException("Tugaskan satu teknisi melalui antrean pekerjaan setelah PSB dibuat")
            return intakes.open(ReferenceWorkSource.PSB, command.subscriptionId,
                WorkOrderType.PSB, command.title, command.description, WorkOrderPriority.NORMAL, command.customerId, command.areaId,
                command.scheduledAt, command.subscriptionId).toRef()
        }
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
                assignees = command.assignees,
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

    @Transactional
    override fun raiseRepair(command: RaiseRepairCommand): WorkOrderRef {
        if (cutovers.read().workflow == WarehouseWorkflow.REFERENCE) {
            val priority = WorkOrderPriority.entries.firstOrNull { it.name == command.priority }
                ?: throw ValidationException("Prioritas tidak dikenal")
            val ticketId = command.ticketId ?: throw ValidationException("Sumber tiket diperlukan")
            return intakes.open(ReferenceWorkSource.HELPDESK, ticketId,
                WorkOrderType.REPAIR, command.title, command.description, priority, command.customerId, null, command.scheduledAt).toRef()
        }
        val priority = WorkOrderPriority.entries.firstOrNull { it.name == command.priority }
            ?: throw ValidationException("Prioritas '${command.priority}' tidak dikenal")
        val view = manageWorkOrder.create(
            SaveWorkOrderCommand(
                type = WorkOrderType.REPAIR,
                title = command.title,
                description = command.description,
                priority = priority,
                customerId = command.customerId,
                subscriptionId = null,
                incidentId = null,
                // Tanpa area: keluhan datang dari meja bantuan, dispatcher yang menempatkannya.
                areaId = null,
                scheduledAt = command.scheduledAt,
                assignees = emptySet(),
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

    override fun fieldOpsReport(from: LocalDate, to: LocalDate): FieldOpsReport {
        val fromInstant = from.atStartOfDay(zone).toInstant()
        val toExclusive = to.plusDays(1).atStartOfDay(zone).toInstant()
        val completed = workOrderRepository.findCompletedBetween(fromInstant, toExclusive)

        // Satu WO dikerjakan tim datar: dibukukan ke SEMUA anggota roster, bukan dibagi rata —
        // "siapa saja yang menuntaskan berapa" lebih berguna bagi penyelia daripada porsi pecahan.
        val technicians = completed
            .flatMap { wo -> wo.assignees.map { it to wo } }
            .groupBy({ it.first }, { it.second })
            .map { (technicianId, orders) ->
                TechnicianProductivity(
                    technicianId = technicianId,
                    completedCount = orders.size,
                    avgResolutionHours = orders.avgHours { it.createdAt to it.completedAt },
                )
            }
            .sortedWith(compareByDescending<TechnicianProductivity> { it.completedCount }.thenBy { it.technicianId })

        return FieldOpsReport(
            completedCount = completed.size,
            completedByType = completed.groupingBy { it.type.name }.eachCount(),
            avgResolutionHours = completed.avgHours { it.createdAt to it.completedAt },
            avgRepairResolutionHours = completed.filter { it.type == WorkOrderType.REPAIR }
                .avgHours { it.createdAt to it.completedAt },
            avgResponseHours = completed.avgHours { it.createdAt to it.startedAt },
            technicians = technicians,
        )
    }

    /**
     * Rata-rata jam antara dua titik waktu sebuah WO; baris yang titik akhirnya belum ada
     * (mis. selesai tanpa pernah ditandai "mulai") dilewati, dan `null` bila tak ada satu pun
     * yang bisa dihitung.
     */
    private fun List<WorkOrder>.avgHours(span: (WorkOrder) -> Pair<Instant, Instant?>): Double? {
        val durations = mapNotNull { wo ->
            val (start, end) = span(wo)
            end?.takeIf { !it.isBefore(start) }?.let { Duration.between(start, it).toMillis() }
        }
        if (durations.isEmpty()) return null
        return durations.average() / MILLIS_PER_HOUR
    }

    private fun WorkOrder.toRef() = WorkOrderRef(
        id = id,
        code = code,
        customerId = customerId!!,
        areaId = areaId,
        scheduledAt = scheduledAt,
    )

    private companion object {
        /** Batas hari→instant memakai zona server, selaras dengan penjadwal (LocalDate.now()). */
        val zone: ZoneId = ZoneId.systemDefault()
        const val MILLIS_PER_HOUR = 3_600_000.0
    }
}
