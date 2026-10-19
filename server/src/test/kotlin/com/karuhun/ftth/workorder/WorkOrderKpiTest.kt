package com.karuhun.ftth.workorder

import com.karuhun.ftth.workorder.application.port.inbound.*
import com.karuhun.ftth.workorder.application.service.KpiWorkOrderChange
import com.karuhun.ftth.workorder.application.service.overdueWorkOrders
import com.karuhun.ftth.workorder.domain.model.WorkOrderPriority
import com.karuhun.ftth.workorder.domain.model.WorkOrderType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

class WorkOrderKpiTest {
    private val first = UUID.randomUUID(); private val second = UUID.randomUUID()
    private val created = Instant.parse("2026-09-27T00:00:00Z")
    private val initial = ReferenceWorkOrderView(UUID.randomUUID(),"WO-1",0,ReferenceWorkOrderTypeView(UUID.randomUUID(),0,"PSB",
        WorkOrderType.PSB,false,listOf("Bukti"),true),"Pelanggan","",WorkOrderPriority.NORMAL,null,first,"A",UUID.randomUUID(),
        null,ReferenceWorkOrderState.PENDING,0,created,null,created)
    @Test fun `overdue persists across month boundaries reassignment completion and detail edits`() {
        val assigned = Instant.parse("2026-10-05T00:00:00Z")
        val done = Instant.parse("2026-10-12T00:00:00Z")
        val history = listOf(KpiWorkOrderChange("CREATE",created,initial),
            KpiWorkOrderChange("UPDATE",Instant.parse("2026-10-01T00:00:00Z"),initial.copy(revision=1)),
            KpiWorkOrderChange("ASSIGN",assigned,initial.copy(revision=2,technicianId=second,lastActivityAt=assigned)),
            KpiWorkOrderChange("COMPLETE",done,initial.copy(revision=3,technicianId=second,state=ReferenceWorkOrderState.COMPLETED,lastActivityAt=done)))
        assertThat(overdueWorkOrders(history,YearMonth.of(2026,9),3,done).map { it.technicianId }).containsExactly(first)
        assertThat(overdueWorkOrders(history,YearMonth.of(2026,10),3,done).map { it.technicianId }).containsExactly(first,second)
        assertThat(overdueWorkOrders(history,YearMonth.of(2026,11),3,done)).isEmpty()
    }
    @Test fun `blocked time is excluded and multiple pending intervals count one order per technician`() {
        val blocked = Instant.parse("2026-09-28T00:00:00Z")
        val progress = Instant.parse("2026-10-06T00:00:00Z")
        val again = Instant.parse("2026-10-12T00:00:00Z")
        val history = listOf(KpiWorkOrderChange("CREATE",created,initial),
            KpiWorkOrderChange("PROGRESS",blocked,initial.copy(revision=1,state=ReferenceWorkOrderState.BLOCKED,lastActivityAt=blocked)),
            KpiWorkOrderChange("PROGRESS",progress,initial.copy(revision=2,lastActivityAt=progress)),
            KpiWorkOrderChange("PHOTO",again,initial.copy(revision=3,lastActivityAt=again)))
        assertThat(overdueWorkOrders(history,YearMonth.of(2026,9),3,again)).isEmpty()
        assertThat(overdueWorkOrders(history,YearMonth.of(2026,10),3,Instant.parse("2026-10-20T00:00:00Z"))).hasSize(1)
    }
    @Test fun `local month end excludes overdue beginning exactly in next Jakarta month`() {
        val view = initial.copy(lastActivityAt=Instant.parse("2026-10-28T17:00:00Z"))
        val history = listOf(KpiWorkOrderChange("CREATE",view.lastActivityAt,view))
        val now = Instant.parse("2026-11-02T00:00:00Z")
        assertThat(overdueWorkOrders(history,YearMonth.of(2026,10),3,now)).isEmpty()
        assertThat(overdueWorkOrders(history,YearMonth.of(2026,11),3,now)).hasSize(1)
    }
}
