package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.iam.FieldTechnician
import com.karuhun.ftth.iam.FieldTeam
import com.karuhun.ftth.workorder.KpiCompletedWorkOrder
import com.karuhun.ftth.workorder.KpiLateWorkOrder
import com.karuhun.ftth.workorder.WorkOrderKpiFacts
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class KpiMetric(val key: String, val label: String, val actual: Int, val target: Int, val percentage: Double)
data class KpiB2BClient(val id: UUID, val name: String, val technicianId: UUID, val target: Int, val visits: Int)
data class TechnicianKpiRow(val technician: FieldTechnician, val metrics: List<KpiMetric>, val percentage: Double,
    val overdue: List<KpiLateWorkOrder>, val completed: List<KpiCompletedWorkOrder>, val clients: List<KpiB2BClient>)
data class TechnicianKpiView(val month: YearMonth, val workingDays: Int, val saturdayOff: Boolean,
    val technicians: List<FieldTechnician>, val rows: List<TechnicianKpiRow>, val average: Double, val calendarError: String? = null)

internal fun kpiPercentage(actual: Int, target: Int): Double = if(target==0) 100.0 else minOf(100.0,actual.toDouble()/target*100)
internal fun calculateTechnicianKpi(technician: FieldTechnician, workingDates: Set<LocalDate>, credits: List<ReportCredit>,
    settings: FieldOperationalSettings, workOrders: WorkOrderKpiFacts, clients: List<KpiB2BClient>, activations: Int): TechnicianKpiRow {
    val reports = credits.filter { it.technicianId==technician.id && it.date in workingDates }.distinctBy { it.date to it.slot }
    val attendance = reports.map { it.date }.distinct().size
    val metrics = mutableListOf(
        KpiMetric("attendance","Kehadiran",attendance,workingDates.size,kpiPercentage(attendance,workingDates.size)),
        KpiMetric("reports","Laporan pagi / sore",reports.size,workingDates.size*2,kpiPercentage(reports.size,workingDates.size*2)))
    val overdue = workOrders.overdue.filter { it.technicianId==technician.id }.distinctBy { it.id }
    val completed = workOrders.completed.filter { it.technicianId==technician.id }.distinctBy { it.id }
    val assigned = clients.filter { it.technicianId==technician.id }
    if(technician.team==FieldTeam.FO) {
        val points = completed.sumOf { it.points }
        metrics += KpiMetric("overdue","WO overdue",overdue.size,settings.foOverdueLimit,maxOf(0.0,100.0-overdue.size.toDouble()/settings.foOverdueLimit*100))
        metrics += KpiMetric("points","PSB / Project",points,settings.foPointTarget,kpiPercentage(points,settings.foPointTarget))
    } else {
        val visits = assigned.sumOf { minOf(it.visits,it.target) }; val quota = assigned.sumOf { it.target }
        metrics += KpiMetric("visits","Visit B2B",visits,quota,kpiPercentage(visits,quota))
        metrics += KpiMetric("activation","Aktivasi B2B",activations,1,kpiPercentage(activations,1))
    }
    return TechnicianKpiRow(technician,metrics,metrics.map { it.percentage }.average(),overdue,completed,assigned)
}
