package com.duluin.ftth.workorder

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderEventType
import com.duluin.ftth.workorder.domain.model.WorkOrderPriority
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import com.duluin.ftth.workorder.domain.model.ProofArtifactKind
import com.duluin.ftth.workorder.domain.model.ProofArtifactRef
import com.duluin.ftth.workorder.domain.model.ProofOfWorkPacket
import com.duluin.ftth.workorder.domain.model.ProofArtifactCompatibility
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** Menguji penugasan tim datar (roster jamak) sebuah work order — murni domain. */
class WorkOrderTest {

    private fun packet(type: WorkOrderType): ProofOfWorkPacket = ProofOfWorkPacket(
        revision = "proof-revision",
        artifacts = com.duluin.ftth.workorder.domain.model.ProofOfWorkPolicy.requiredArtifacts(type)
            .map { ProofArtifactRef(it, UuidV7.generate()) }.toSet(),
    )

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

    @Test
    fun `complete menolak packet yang kehilangan satu artefak`() {
        val wo = draft()
        wo.assign(setOf(UuidV7.generate()), Instant.now(), null)
        wo.start(Instant.now(), null)
        val incomplete = packet(WorkOrderType.REPAIR).copy(artifacts = packet(WorkOrderType.REPAIR).artifacts.drop(1).toSet())

        assertThatThrownBy { wo.complete(null, incomplete, Instant.now(), UuidV7.generate()) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(wo.status).isEqualTo(WorkOrderStatus.IN_PROGRESS)
    }

    @Test
    fun `complete menghasilkan DONE pending tanpa efek pelanggan dan approval maker checker`() {
        val wo = draft()
        val technician = UuidV7.generate()
        val approver = UuidV7.generate()
        wo.assign(setOf(technician), Instant.now(), null)
        wo.start(Instant.now(), technician)
        wo.complete(null, packet(WorkOrderType.REPAIR), Instant.now(), technician)

        assertThat(wo.status).isEqualTo(WorkOrderStatus.DONE)
        assertThat(wo.approvalStatus).isEqualTo(com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus.PENDING)
        assertThatThrownBy { wo.approve(null, Instant.now(), technician) }.isInstanceOf(ConflictException::class.java)
        wo.approve(null, Instant.now(), approver)
        assertThat(wo.approvalStatus).isEqualTo(com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus.APPROVED)
        assertThatThrownBy { wo.approve(null, Instant.now(), UuidV7.generate()) }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `setiap artefak wajib yang hilang ditolak secara deterministik`() {
        val type = WorkOrderType.PSB
        val complete = packet(type)
        com.duluin.ftth.workorder.domain.model.ProofOfWorkPolicy.requiredArtifacts(type).forEach { missing ->
            val partial = complete.copy(artifacts = complete.artifacts.filterNot { it.kind == missing }.toSet())
            assertThatThrownBy { partial.validateFor(type) }
                .isInstanceOf(ConflictException::class.java)
        }
    }

    @Test
    fun `satu revisi tidak boleh dipakai ulang untuk dua jenis bukti`() {
        val revisionId = UuidV7.generate()

        assertThatThrownBy {
            ProofOfWorkPacket(
                revision = "proof-revision",
                artifacts = setOf(
                    ProofArtifactRef(ProofArtifactKind.FAT, revisionId),
                    ProofArtifactRef(ProofArtifactKind.ODP, revisionId),
                ),
            )
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `revisi otoritatif dengan jenis berbeda ditolak`() {
        val revisionId = UuidV7.generate()

        assertThatThrownBy {
            ProofArtifactCompatibility.requireMatching(
                setOf(ProofArtifactRef(ProofArtifactKind.FAT, revisionId)),
                mapOf(revisionId to ProofArtifactKind.ODP),
            )
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `penolakan membuka kembali pekerjaan untuk submission baru`() {
        val wo = draft()
        val technician = UuidV7.generate()
        wo.assign(setOf(technician), Instant.now(), null)
        wo.start(Instant.now(), technician)
        wo.complete(null, packet(WorkOrderType.REPAIR), Instant.now(), technician)
        wo.reject("Foto lokasi tidak jelas", Instant.now(), UuidV7.generate())

        assertThat(wo.status).isEqualTo(WorkOrderStatus.IN_PROGRESS)
        assertThat(wo.completedAt).isNull()
    }
}
