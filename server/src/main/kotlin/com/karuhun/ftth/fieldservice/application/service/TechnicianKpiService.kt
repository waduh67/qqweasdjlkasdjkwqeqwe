package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.B2BStore
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.DailyReportStore
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.uuid
import com.karuhun.ftth.iam.FieldTeam
import com.karuhun.ftth.workorder.WorkOrderKpiApi
import com.karuhun.ftth.workorder.WorkOrderKpiFacts
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth
import java.util.UUID

@Service
@Transactional
class TechnicianKpiService(private val access: FieldAccess, private val calendar: WorkCalendarService, private val reports: DailyReportStore,
    private val b2b: B2BStore, private val workOrders: WorkOrderKpiApi, private val clock: B2BOperationalClock) {
    fun calendar(month: YearMonth): WorkCalendarView {
        validateMonth(month); access.scope(access.actor(),"kpi.technician.view","kpi.team.view")
        return calendar.effectiveCalendar(month.year)
    }
    fun read(month: YearMonth, team: FieldTeam?, technicianId: UUID?): TechnicianKpiView {
        validateMonth(month)
        val members = access.scope(access.actor(),"kpi.technician.view","kpi.team.view")
        if(technicianId!=null && members.none { it.id==technicianId }) throw NotFoundException("Teknisi tidak ditemukan")
        val selected = members.filter { (team==null || it.team==team) && (technicianId==null || it.id==technicianId) }
        val dates = calendar.effectiveCalendar(month.year); val settings = calendar.effectiveSettings()
        val credits = reports.credits(month)
        val facts = if(selected.any { it.team==FieldTeam.FO }) workOrders.monthlyFacts(month,clock.instant()) else WorkOrderKpiFacts(emptyList(),emptyList())
        val (clients,activations) = b2b.use { sql ->
            sql.lock(); sql.prepare(month.atDay(1))
            val clients = sql.query("""SELECT m.client_id,m.name,m.technician_id,m.target,count(v.id) visits FROM b2b_client_month m
                LEFT JOIN b2b_visit v ON v.tenant_id=m.tenant_id AND v.client_id=m.client_id AND v.month=m.month AND v.counted
                WHERE m.tenant_id=? AND m.month=? AND m.active GROUP BY m.client_id,m.name,m.technician_id,m.target""",sql.tenant,month.atDay(1)) {
                KpiB2BClient(it.uuid("client_id"),it.getString("name"),it.uuid("technician_id"),it.getInt("target"),it.getInt("visits"))
            }
            val activations = sql.query("""SELECT m.technician_id,count(*) activations FROM b2b_client c JOIN b2b_client_month m
                ON m.tenant_id=c.tenant_id AND m.client_id=c.id AND m.month=c.created_month
                WHERE c.tenant_id=? AND c.created_month=? AND m.active GROUP BY m.technician_id""",sql.tenant,month.atDay(1)) {
                it.uuid("technician_id") to it.getInt("activations")
            }.toMap()
            clients to activations
        }
        val working = dates.workingDates(month)
        val rows = selected.map { calculateTechnicianKpi(it,working,credits,settings,facts,clients,activations[it.id] ?: 0) }
        return TechnicianKpiView(month,working.size,settings.saturdayOff,members,rows,if(rows.isEmpty()) 0.0 else rows.map { it.percentage }.average())
    }
    private fun validateMonth(month: YearMonth) {
        if(month.year<2000 || month>YearMonth.from(clock.today())) throw ValidationException("Pilih bulan dari Januari 2000 hingga bulan ini")
    }
}
