package com.duluin.ftth.collector.adapter.junos

import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPayloadValues
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningTarget
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JunosProvisioningAdapterTest {
    @Test
    fun `EX transport apply locks merges validates confirms verifies and unlocks`() {
        val fixture = JunosSessionFixture(EX_IDENTITY)
        val adapter = adapter(fixture)

        val result = adapter.execute(target(), command(TRANSPORT_OPERATION))

        assertTrue(result.success)
        assertTrue(assertNotNull(result.verification).matchesExpected)
        assertEquals(
            listOf(
                "hello",
                "observe:before",
                "lock:candidate",
                "edit:candidate:MERGE",
                "validate:candidate",
                "commit:confirmed:120",
                "observe:after",
                "commit:confirm:commit-1",
                "unlock:candidate",
                "close",
            ),
            fixture.events,
        )
    }

    @Test
    fun `MX explicitly supported PPPoE operation uses subscriber configuration`() {
        val fixture = JunosSessionFixture(MX_IDENTITY)
        val adapter = adapter(fixture)

        val result = adapter.execute(target(), command(PPPOE_OPERATION, pppoePayload()))

        assertTrue(result.success)
        assertTrue("<subscribers>" in assertNotNull(fixture.lastChange).configuration)
    }

    @Test
    fun `EX rejects PPPoE before candidate edit`() {
        val fixture = JunosSessionFixture(EX_IDENTITY)
        val adapter = adapter(fixture)

        val result = adapter.execute(target(), command(PPPOE_OPERATION, pppoePayload()))

        assertEquals(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY, result.errorCode)
        assertFalse(fixture.events.any { it.startsWith("edit:") })
        assertFalse(fixture.events.any { it.startsWith("commit:") })
    }

    @Test
    fun `lock contention returns stale precondition without unlock`() {
        val fixture = JunosSessionFixture(EX_IDENTITY, failure = FixtureFailure.LOCK_CONTENTION)

        val result = adapter(fixture).execute(target(), command(TRANSPORT_OPERATION))

        assertEquals(ProvisioningErrorCode.STALE_PRECONDITION, result.errorCode)
        assertFalse("unlock:candidate" in fixture.events)
    }

    @Test
    fun `validation error discards candidate and unlocks`() {
        val fixture = JunosSessionFixture(EX_IDENTITY, failure = FixtureFailure.VALIDATION)

        val result = adapter(fixture).execute(target(), command(TRANSPORT_OPERATION))

        assertEquals(ProvisioningErrorCode.VALIDATION_FAILED, result.errorCode)
        assertTrue(fixture.events.indexOf("discard:candidate") < fixture.events.indexOf("unlock:candidate"))
    }

    @Test
    fun `missing model fails closed before edit`() {
        val fixture = JunosSessionFixture(JunosDeviceIdentity("EX9999", "1.0"))

        val result = adapter(fixture).execute(target(), command(TRANSPORT_OPERATION))

        assertEquals(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY, result.errorCode)
        assertFalse(fixture.events.any { it.startsWith("edit:") })
    }

    @Test
    fun `verification failure preserves automatic rollback and records identifier`() {
        val fixture = JunosSessionFixture(EX_IDENTITY, failure = FixtureFailure.VERIFICATION)
        val journal = InMemoryJunosRollbackJournal()
        val adapter = adapter(fixture, journal)

        val result = adapter.execute(target(), command(TRANSPORT_OPERATION))

        assertEquals(ProvisioningErrorCode.VERIFICATION_MISMATCH, result.errorCode)
        val rollback = assertNotNull(journal.find(STEP_KEY))
        assertEquals("rollback-17", rollback.rollbackId)
        assertEquals(JunosRollbackStatus.AUTOMATIC_COMPLETED, rollback.status)
        assertTrue(fixture.events.indexOf("rollback:await:rollback-17") < fixture.events.indexOf("unlock:candidate"))
        assertFalse(fixture.events.any { it == "commit:confirm:commit-1" })
    }

    @Test
    fun `expired confirmation records native rollback and returns timeout`() {
        val fixture = JunosSessionFixture(EX_IDENTITY, failure = FixtureFailure.CONFIRMATION_EXPIRED)
        val journal = InMemoryJunosRollbackJournal()

        val result = adapter(fixture, journal).execute(target(), command(TRANSPORT_OPERATION))

        assertEquals(ProvisioningErrorCode.TIMEOUT, result.errorCode)
        assertEquals(JunosRollbackStatus.AUTOMATIC_COMPLETED, journal.find(STEP_KEY)?.status)
        assertTrue("rollback:await:rollback-17" in fixture.events)
    }

    @Test
    fun `capability report is exact and credentials are redacted`() {
        val fixture = JunosSessionFixture(EX_IDENTITY)
        val connection = JunosConnection("router.invalid", 830, "operator", "top-secret")

        val report = adapter(fixture).capabilityReport(target())

        assertEquals("EX4300-48P", report.fingerprint.model)
        assertEquals(setOf(TRANSPORT_OPERATION), report.operationClasses)
        assertTrue("CERTIFICATION_PROVISIONAL" in report.capabilities)
        assertFalse(connection.toString().contains("operator"))
        assertFalse(connection.toString().contains("top-secret"))
    }

    private fun adapter(
        fixture: JunosSessionFixture,
        journal: JunosRollbackJournal = InMemoryJunosRollbackJournal(),
    ) = JunosProvisioningAdapter(
        sessionFactory = { fixture },
        rollbackJournal = journal,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun target() = NasTarget(
        nasId = "junos-1",
        name = "junos",
        vendor = "JUNIPER",
        host = "router.invalid",
        adapterType = "JUNOS_NETCONF",
        apiUsername = "operator",
        apiSecret = "top-secret",
        apiPort = 830,
    )

    private fun command(operation: String, payload: ProvisioningPayload = transportPayload()) = ProvisioningPlanStepCommand(
        planId = "plan-1",
        revision = 1,
        stepId = "step-1",
        phase = ProvisioningCommandPhase.APPLY,
        operationClass = operation,
        idempotencyKey = "task-8-$operation",
        deadline = NOW.plusSeconds(300),
        target = ProvisioningTarget("junos-1", "SWITCH", "router.invalid", "NETCONF_SSH"),
        payload = payload,
    )

    private class JunosSessionFixture(
        private val identity: JunosDeviceIdentity,
        private val failure: FixtureFailure? = null,
    ) : JunosNetconfSession {
        val events = mutableListOf<String>()
        var lastChange: JunosCandidateChange? = null
        private var observations = 0

        override fun hello(): JunosHello {
            events += "hello"
            return JunosHello(identity, REQUIRED_CAPABILITIES)
        }

        override fun observe(change: JunosCandidateChange): JunosOperationalObservation {
            val label = if (observations++ == 0) "before" else "after"
            events += "observe:$label"
            if (label == "before" || failure == FixtureFailure.VERIFICATION) {
                return JunosOperationalObservation(emptySet(), managementReachable = true)
            }
            return JunosOperationalObservation(change.expectedResources, managementReachable = true)
        }

        override fun lockCandidate() {
            events += "lock:candidate"
            if (failure == FixtureFailure.LOCK_CONTENTION) throw JunosLockDeniedException()
        }

        override fun editCandidate(change: JunosCandidateChange) {
            events += "edit:candidate:${change.defaultOperation}"
            lastChange = change
        }

        override fun validateCandidate() {
            events += "validate:candidate"
            if (failure == FixtureFailure.VALIDATION) throw JunosValidationException("invalid fixture edit")
        }

        override fun commitConfirmed(timeoutSeconds: Int): JunosConfirmedCommit {
            events += "commit:confirmed:$timeoutSeconds"
            return JunosConfirmedCommit("commit-1", "rollback-17", NOW.plusSeconds(timeoutSeconds.toLong()))
        }

        override fun confirmCommit(commitId: String) {
            events += "commit:confirm:$commitId"
            if (failure == FixtureFailure.CONFIRMATION_EXPIRED) throw JunosConfirmationExpiredException()
        }

        override fun awaitAutomaticRollback(rollbackId: String): JunosRollbackReceipt {
            events += "rollback:await:$rollbackId"
            return JunosRollbackReceipt(rollbackId, JunosOperationalObservation(emptySet(), managementReachable = true))
        }

        override fun discardCandidate() {
            events += "discard:candidate"
        }

        override fun unlockCandidate() {
            events += "unlock:candidate"
        }

        override fun close() {
            events += "close"
        }
    }

    private enum class FixtureFailure { LOCK_CONTENTION, VALIDATION, VERIFICATION, CONFIRMATION_EXPIRED }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-02T12:00:00Z")
        val EX_IDENTITY = JunosDeviceIdentity("EX4300-48P", "21.4R3-S5.4")
        val MX_IDENTITY = JunosDeviceIdentity("MX204", "23.4R2-S2.1")
        val REQUIRED_CAPABILITIES = setOf(
            "urn:ietf:params:netconf:capability:candidate:1.0",
            "urn:ietf:params:netconf:capability:confirmed-commit:1.1",
            "urn:ietf:params:netconf:capability:validate:1.1",
        )
        const val TRANSPORT_OPERATION = "ENSURE_TAGGED_VLAN"
        const val PPPOE_OPERATION = "ENSURE_PPPOE_TERMINATION"
        const val STEP_KEY = "plan-1:1:step-1"

        fun transportPayload() = ProvisioningPayload(
            ProvisioningPayloadValues(
                tenantId = "tenant-1",
                intentId = "intent-1",
                vlanId = "110",
                trunkPorts = "ge-0/0/0",
                accessPorts = "ge-0/0/1",
                vlanInterface = "irb.110",
                firewallChain = "ftth-110",
            ),
        )

        fun pppoePayload() = ProvisioningPayload(
            ProvisioningPayloadValues(
                tenantId = "tenant-1",
                intentId = "intent-2",
                vlanId = "210",
                pppoeInterface = "demux0.210",
                pppoeServiceName = "fiber",
                poolName = "pool-210",
                poolRanges = "10.210.0.2-10.210.0.254",
            ),
        )
    }
}
