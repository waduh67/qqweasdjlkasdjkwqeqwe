package com.karuhun.ftth.fieldservice

import com.karuhun.ftth.fieldservice.application.service.*
import com.karuhun.ftth.iam.FieldTeam
import com.karuhun.ftth.iam.FieldTechnician
import com.karuhun.ftth.workorder.KpiCompletedWorkOrder
import com.karuhun.ftth.workorder.KpiLateWorkOrder
import com.karuhun.ftth.workorder.WorkOrderKpiFacts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class TechnicianKpiTest {
    private val technician = FieldTechnician(UUID.randomUUID(),"NE",FieldTeam.NE)
    private val date = LocalDate.of(2026,10,16)
    private val emptyOrders = WorkOrderKpiFacts(emptyList(),emptyList())
    @Test fun `NE caps visits separately and excludes holiday and duplicate report credits`() {
        val credits = listOf(ReportCredit(technician.id,date,ReportSlot.MORNING),ReportCredit(technician.id,date,ReportSlot.EVENING),
            ReportCredit(technician.id,date,ReportSlot.MORNING),ReportCredit(technician.id,date.plusDays(1),ReportSlot.MORNING),
            ReportCredit(UUID.randomUUID(),date,ReportSlot.MORNING))
        val clients = listOf(KpiB2BClient(UUID.randomUUID(),"A",technician.id,4,6),KpiB2BClient(UUID.randomUUID(),"B",technician.id,4,0))
        val row = calculateTechnicianKpi(technician,setOf(date),credits,FieldOperationalSettings(),emptyOrders,clients,0)
        assertThat(row.metrics.map { it.actual }).containsExactly(1,2,4,0)
        assertThat(row.metrics.map { it.percentage }).containsExactly(100.0,100.0,50.0,0.0)
        assertThat(row.percentage).isEqualTo(62.5)
    }
    @Test fun `no working dates and no assigned quota earn full credit while activations cap at one`() {
        val row = calculateTechnicianKpi(technician,emptySet(),emptyList(),FieldOperationalSettings(),emptyOrders,emptyList(),3)
        assertThat(row.metrics.map { it.percentage }).containsExactly(100.0,100.0,100.0,100.0)
        assertThat(row.percentage).isEqualTo(100.0)
    }
    @Test fun `FO uses distinct overdue orders weighted frozen points and live targets`() {
        val fo = technician.copy(team=FieldTeam.FO)
        val overdue = KpiLateWorkOrder(UUID.randomUUID(),"WO-1","A",fo.id,Instant.EPOCH)
        val project = KpiCompletedWorkOrder(UUID.randomUUID(),"WO-2","B",fo.id,Instant.EPOCH,"PROJECT",3)
        val facts = WorkOrderKpiFacts(listOf(overdue,overdue),listOf(project,project))
        val row = calculateTechnicianKpi(fo,emptySet(),emptyList(),FieldOperationalSettings(),facts,emptyList(),0)
        assertThat(row.metrics.map { it.percentage }).containsExactly(100.0,100.0,75.0,20.0)
        assertThat(row.percentage).isEqualTo(73.75)
        val changed = calculateTechnicianKpi(fo,emptySet(),emptyList(),FieldOperationalSettings(foOverdueLimit=1,foPointTarget=2),facts,emptyList(),0)
        assertThat(changed.metrics.map { it.percentage }).containsExactly(100.0,100.0,0.0,100.0)
    }
}
