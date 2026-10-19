package com.karuhun.ftth.workorder

import java.time.Instant
import java.time.YearMonth
import java.util.UUID

interface WorkOrderKpiApi {
    fun monthlyFacts(month: YearMonth, now: Instant): WorkOrderKpiFacts
}
data class KpiLateWorkOrder(val id: UUID, val code: String, val title: String, val technicianId: UUID, val overdueAt: Instant)
data class KpiCompletedWorkOrder(val id: UUID, val code: String, val title: String, val technicianId: UUID,
    val completedAt: Instant, val category: String, val points: Int)
data class WorkOrderKpiFacts(val overdue: List<KpiLateWorkOrder>, val completed: List<KpiCompletedWorkOrder>)
