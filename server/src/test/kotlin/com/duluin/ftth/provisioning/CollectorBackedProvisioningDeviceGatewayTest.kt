package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.provisioning.adapter.outbound.persistence.CollectorBackedProvisioningDeviceGateway
import com.duluin.ftth.provisioning.adapter.outbound.persistence.CollectorResultReceipt
import com.duluin.ftth.provisioning.adapter.outbound.persistence.CollectorResultReceiptReader
import com.duluin.ftth.provisioning.adapter.outbound.persistence.parseReceiptVlanIds
import com.duluin.ftth.provisioning.application.service.DeviceOperationException
import com.duluin.ftth.provisioning.application.service.DispatchableProvisioningWork
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ExecutionPhase
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CollectorBackedProvisioningDeviceGatewayTest {
    private val now = Instant.parse("2026-09-03T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `observe rejects missing verification outcome`() {
        val work = work(ExecutionPhase.VERIFY, "missing-verification")
        val gateway = gateway(work, receipt(work, null, listOf(320)))

        assertFailure("COLLECTOR_RECEIPT_VERIFICATION_MISSING") { gateway.observe(work) }
    }

    @Test
    fun `verification rejects explicit mismatch`() {
        val work = work(ExecutionPhase.VERIFY, "verification-mismatch")
        val gateway = gateway(work, receipt(work, false, listOf(320)))

        assertFailure("VERIFICATION_MISMATCH") { gateway.observe(work) }
    }

    @Test
    fun `apply rejects missing normalized state instead of using desired attributes`() {
        val work = work(ExecutionPhase.APPLY, "missing-apply-state")
        val gateway = gateway(work, receipt(work, true, null))

        assertFailure("COLLECTOR_RECEIPT_STATE_MISSING") { gateway.apply(work) }
    }

    @Test
    fun `apply accepts explicitly present empty normalized state`() {
        val work = work(ExecutionPhase.APPLY, "explicit-empty-state")
        val gateway = gateway(work, receipt(work, true, emptyList()))

        val applied = gateway.apply(work)

        assertThat(applied.state.values).isEmpty()
    }

    @Test
    fun `receipt parser distinguishes absent empty and malformed state`() {
        assertThat(parseReceiptVlanIds(null)).isNull()
        assertThat(parseReceiptVlanIds("")).isEmpty()
        assertFailure("COLLECTOR_RECEIPT_STATE_MALFORMED") { parseReceiptVlanIds("320,invalid") }
    }

    @Test
    fun `compensation rejects state that does not prove before state`() {
        val work = work(ExecutionPhase.COMPENSATE, "rollback-mismatch")
        val before = state(320)
        val gateway = gateway(work, receipt(work, true, listOf(321)))

        assertFailure("ROLLBACK_VERIFICATION_MISMATCH") { gateway.compensate(work, before) }
    }

    @Test
    fun `compensation returns collector proven before state`() {
        val work = work(ExecutionPhase.COMPENSATE, "rollback-match")
        val before = state(320)
        val gateway = gateway(work, receipt(work, true, listOf(320)))

        val compensated = gateway.compensate(work, before)

        assertThat(compensated.state).isEqualTo(before)
    }

    @Test
    fun `gateway rejects mismatched idempotency identity`() {
        val work = work(ExecutionPhase.APPLY, "identity-key")
        val malformed = receipt(work, true, listOf(320)).copy(idempotencyKey = "other-key")

        assertFailure("COLLECTOR_RECEIPT_IDENTITY_MISMATCH") { gateway(work, malformed).apply(work) }
    }

    @Test
    fun `gateway rejects mismatched phase identity`() {
        val work = work(ExecutionPhase.APPLY, "identity-phase")
        val malformed = receipt(work, true, listOf(320)).copy(phase = "VERIFY")

        assertFailure("COLLECTOR_RECEIPT_IDENTITY_MISMATCH") { gateway(work, malformed).apply(work) }
    }

    @Test
    fun `gateway rejects mismatched fencing identity`() {
        val work = work(ExecutionPhase.APPLY, "identity-fence")
        val malformed = receipt(work, true, listOf(320)).copy(fencingEpoch = work.fencingToken + 1)

        assertFailure("COLLECTOR_RECEIPT_IDENTITY_MISMATCH") { gateway(work, malformed).apply(work) }
    }

    private fun gateway(work: DispatchableProvisioningWork, receipt: CollectorResultReceipt): CollectorBackedProvisioningDeviceGateway {
        val reader = mock(CollectorResultReceiptReader::class.java)
        `when`(reader.find(work.idempotencyKey, work.phase, work.fencingToken)).thenReturn(receipt)
        return CollectorBackedProvisioningDeviceGateway(reader, clock)
    }

    private fun work(phase: ExecutionPhase, key: String) = DispatchableProvisioningWork(
        UuidV7.generate(), UuidV7.generate(), 1, UuidV7.generate(),
        DeviceReference(DeviceKind.OLT, UuidV7.generate()), ProvisionOperation.ENSURE_ACCESS_PORT,
        phase, key, 7, "a".repeat(64), now.plusSeconds(30), mapOf("vlanId" to "320"),
    )

    private fun receipt(
        work: DispatchableProvisioningWork,
        verificationMatches: Boolean?,
        vlanIds: List<Int>?,
    ) = CollectorResultReceipt(
        work.idempotencyKey,
        wirePhase(work.phase),
        work.fencingToken,
        true,
        null,
        verificationMatches,
        vlanIds,
    )

    private fun wirePhase(phase: ExecutionPhase): String = when (phase) {
        ExecutionPhase.PREFLIGHT, ExecutionPhase.ROLLBACK_CHECK -> "PREFLIGHT"
        ExecutionPhase.APPLY -> "APPLY"
        ExecutionPhase.VERIFY, ExecutionPhase.ROLLBACK_VERIFY -> "VERIFY"
        ExecutionPhase.COMPENSATE -> "ROLLBACK"
    }

    private fun state(vlanId: Int) = NormalizedDeviceState.of(
        NormalizedField.VLANS to NormalizedValue.sequence(NormalizedValue.number(vlanId)),
    )

    private fun assertFailure(code: String, operation: () -> Unit) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(DeviceOperationException::class.java) {
            assertThat(it.code).isEqualTo(code)
        }
    }
}
