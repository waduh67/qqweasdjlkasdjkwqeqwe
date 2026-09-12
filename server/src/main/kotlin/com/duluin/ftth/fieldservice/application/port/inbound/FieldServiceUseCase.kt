package com.duluin.ftth.fieldservice.application.port.inbound

import com.duluin.ftth.fieldservice.VisitCancellationCause
import com.duluin.ftth.fieldservice.domain.model.AttendanceDecision
import com.duluin.ftth.fieldservice.domain.model.CommandMetadata
import com.duluin.ftth.fieldservice.domain.model.Visit
import java.time.Instant
import java.util.UUID

interface FieldServiceUseCase {
    fun create(command: CreateVisitCommand): Visit
    fun checkIn(visitId: UUID, command: CommandMetadata, receivedAt: Instant, decision: AttendanceDecision, reason: String?): Visit
    fun onSite(visitId: UUID, command: CommandMetadata, receivedAt: Instant): Visit
    fun checkOut(visitId: UUID, command: CommandMetadata, receivedAt: Instant): Visit
    fun submit(visitId: UUID, command: CommandMetadata, receivedAt: Instant): Visit

    /**
     * Kunjungan gagal di lapangan. [cause] WAJIB di jalur ini — lihat [VisitCancellationCause]:
     * sebab inilah satu-satunya bit yang dipakai memutuskan apakah pesanannya sekarang
     * "menunggu pelanggan" di portal.
     */
    fun cancel(visitId: UUID, command: CommandMetadata, cause: VisitCancellationCause, reason: String, receivedAt: Instant): Visit
}

data class CreateVisitCommand(
    val tenantId: UUID,
    val orderId: UUID,
    val workOrderId: UUID,
    val technicianId: UUID,
    val plannedAt: Instant,
    val operation: CommandMetadata,
)
