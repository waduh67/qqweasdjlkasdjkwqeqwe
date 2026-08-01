package com.duluin.ftth.workorder

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderEventType
import com.duluin.ftth.workorder.domain.model.WorkOrderPriority
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** Menguji penugasan tim datar (roster jamak) sebuah work order — murni domain. */
class WorkOrderTest {

    private fun draft() = WorkOrder.open(
        tenantId = UuidV7.generate(),
        type = WorkOrderType.REPAIR,
        title = "Perbaikan drop",
        description = null,
        priority = WorkOrderPriority.NORMAL,
        customerId = null,
        incidentId = null,
        areaId = null,
        scheduledAt = null,
        createdBy = null,
    )

    @Test
    fun `work order baru belum punya roster teknisi`() {
        val wo = draft()
        assertThat(wo.status).isEqualTo(WorkOrderStatus.DRAFT)
        assertThat(wo.assignees).isEmpty()
    }

    @Test
    fun `menugaskan banyak teknisi menaikkan DRAFT ke ASSIGNED`() {
        val wo = draft()
        val a = UuidV7.generate()
        val b = UuidV7.generate()

        wo.assign(setOf(a, b), Instant.now(), actorId = null)

        assertThat(wo.status).isEqualTo(WorkOrderStatus.ASSIGNED)
        assertThat(wo.assignees).containsExactlyInAnyOrder(a, b)
        assertThat(wo.assignedAt).isNotNull()
        assertThat(wo.isAssignedTo(a)).isTrue()
        assertThat(wo.isAssignedTo(b)).isTrue()
        assertThat(wo.isAssignedTo(UuidV7.generate())).isFalse()
    }

    @Test
    fun `menugaskan ulang mengganti roster utuh — bukan menambah`() {
        val wo = draft()
        val a = UuidV7.generate()
        val b = UuidV7.generate()
        val c = UuidV7.generate()
        wo.assign(setOf(a, b), Instant.now(), actorId = null)

        wo.assign(setOf(c), Instant.now(), actorId = null)

        assertThat(wo.assignees).containsExactly(c)
        assertThat(wo.isAssignedTo(a)).isFalse()
        assertThat(wo.isAssignedTo(b)).isFalse()
    }

    @Test
    fun `roster tanpa teknisi ditolak`() {
        val wo = draft()
        assertThatThrownBy { wo.assign(emptySet(), Instant.now(), actorId = null) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `work order terminal tak bisa ditugaskan`() {
        val wo = draft()
        wo.cancel("batal", Instant.now(), actorId = null)
        assertThatThrownBy { wo.assign(setOf(UuidV7.generate()), Instant.now(), actorId = null) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `open dengan roster awal langsung ASSIGNED`() {
        val a = UuidV7.generate()
        val b = UuidV7.generate()
        val wo = WorkOrder.open(
            tenantId = UuidV7.generate(),
            type = WorkOrderType.PSB,
            title = "Pasang baru",
            description = null,
            priority = WorkOrderPriority.NORMAL,
            customerId = null,
            incidentId = null,
            areaId = null,
            scheduledAt = null,
            assignees = setOf(a, b),
            createdBy = null,
        )

        assertThat(wo.status).isEqualTo(WorkOrderStatus.ASSIGNED)
        assertThat(wo.assignees).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `label event penugasan menyebut jumlah teknisi ketika lebih dari satu`() {
        val wo = draft()
        wo.assign(setOf(UuidV7.generate(), UuidV7.generate(), UuidV7.generate()), Instant.now(), actorId = null)

        val label = wo.pendingEvents().last { it.type == WorkOrderEventType.ASSIGNED }.message
        assertThat(label).isEqualTo("Ditugaskan ke 3 teknisi")
    }

    @Test
    fun `label event penugasan tunggal tetap singular`() {
        val wo = draft()
        wo.assign(setOf(UuidV7.generate()), Instant.now(), actorId = null)

        val label = wo.pendingEvents().last { it.type == WorkOrderEventType.ASSIGNED }.message
        assertThat(label).isEqualTo("Ditugaskan ke teknisi")
    }
}
