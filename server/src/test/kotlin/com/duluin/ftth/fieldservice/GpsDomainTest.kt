package com.duluin.ftth.fieldservice

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.fieldservice.application.port.outbound.InMemoryGpsPointRepository
import com.duluin.ftth.fieldservice.application.service.GpsCaptureService
import com.duluin.ftth.fieldservice.application.service.GpsRetentionWorker
import com.duluin.ftth.fieldservice.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GpsDomainTest {
    private val tenant = UUID.randomUUID()
    private val visit = UUID.randomUUID()
    private val session = UUID.randomUUID()
    private val technician = UUID.randomUUID()
    private val received = Instant.parse("2026-09-04T10:00:00Z")
    private val valid = CaptureGpsCommand(
        tenant, visit, session, technician, UUID.randomUUID(),
        106.8, -6.2, 12.0, "fused", Instant.parse("2026-09-04T09:59:30Z"),
        false, GpsPurpose.ONSITE, "gps.capture", "gps.one", "hash-one", 0,
    )

    private fun service() = GpsCaptureService(
        InMemoryGpsPointRepository(),
        { _, actorId -> actorId == technician },
        { _, _ -> true },
    )

    @Test
    fun `valid onsite event is accepted and exact location is absent from customer view`() {
        val result = service().capture(valid, received)

        assertThat(result.decision).isEqualTo(GpsReviewDecision.ACCEPTED)
        assertThat(result.point?.serverReceivedAt).isEqualTo(received)
        assertThat(CustomerGpsProjection.from(result.point!!).exactLocation).isNull()
    }

    @Test
    fun `sentinel and malformed coordinates are rejected`() {
        listOf(valid.copy(latitude = 0.0, longitude = 0.0), valid.copy(latitude = 95.0), valid.copy(longitude = 181.0))
            .forEach { command -> assertThat(service().capture(command, received).decision).isEqualTo(GpsReviewDecision.REJECTED) }
    }

    @Test
    fun `stale and poor accuracy events require review`() {
        val stale = valid.copy(clientOccurredAt = received.minusSeconds(72 * 60 * 60 + 1))
        val poor = valid.copy(accuracyMeters = 250.0, operationKey = "gps.poor", payloadHash = "hash-poor")

        assertThat(service().capture(stale, received).decision).isEqualTo(GpsReviewDecision.REVIEW_REQUIRED)
        assertThat(service().capture(poor, received).decision).isEqualTo(GpsReviewDecision.REVIEW_REQUIRED)
    }

    @Test
    fun `same operation replays and different payload conflicts`() {
        val gps = service()
        val first = gps.capture(valid, received)

        assertThat(gps.capture(valid, received)).isEqualTo(first)
        assertThatThrownBy { gps.capture(valid.copy(payloadHash = "different"), received) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `unassigned actor cannot capture`() {
        val command = valid.copy(actorId = UUID.randomUUID())
        assertThatThrownBy { service().capture(command, received) }.isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `retention purge excludes legal hold and returns deletion evidence`() {
        val repository = InMemoryGpsPointRepository()
        val gps = GpsCaptureService(repository, { _, _ -> true }, { _, _ -> false })
        val old = gps.capture(valid.copy(operationKey = "old", clientOccurredAt = received.minusSeconds(91 * 86400)), received).point!!
        val held = gps.capture(valid.copy(operationKey = "held", clientOccurredAt = received.minusSeconds(91 * 86400)), received).point!!
        val result = GpsRetentionWorker(repository, { setOf(held.id) }) { received.plusSeconds(91 * 86400) }.purge(tenant)

        assertThat(result.pointIds).containsExactly(old.id)
        assertThat(result.pointIds).doesNotContain(held.id)
    }
}
