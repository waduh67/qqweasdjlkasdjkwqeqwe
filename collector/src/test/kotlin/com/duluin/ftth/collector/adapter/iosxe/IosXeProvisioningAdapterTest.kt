package com.duluin.ftth.collector.adapter.iosxe

import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningTarget
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosXeProvisioningAdapterTest {
    @Test
    fun `confirmed commit is finalized only after matching operational verification`() {
        val fixture = Fixture()

        val result = fixture.adapter().execute(fixture.target(), fixture.command())

        assertTrue(result.success)
        assertEquals(
            listOf("LOCK_CANDIDATE", "DISCARD_CHANGES", "EDIT_CANDIDATE", "VALIDATE_CANDIDATE", "CONFIRMED_COMMIT:120", "VERIFY_OPERATIONAL", "FINAL_COMMIT", "UNLOCK_CANDIDATE"),
            fixture.lifecycleEvents(),
        )
        assertEquals(true, result.verification?.matchesExpected)
        assertEquals(true, result.apply?.changed)
    }

    @Test
    fun `missing protocol or exact YANG capability fails before candidate mutation`() {
        val required = IosXeCapabilities.PROTOCOL + IosXeProfiles.CATALYST_9300_17_18.requiredModules
        required.forEach { missing ->
            val fixture = Fixture(capabilities = IosXeCapabilities.PROTOCOL - missing, modules = IosXeProfiles.CATALYST_9300_17_18.requiredModules - missing)

            val result = fixture.adapter().execute(fixture.target(), fixture.command())

            assertFalse(result.success, missing)
            assertEquals(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY, result.errorCode, missing)
            assertTrue(fixture.lifecycleEvents().isEmpty(), missing)
        }
    }

    @Test
    fun `unknown model firmware combination remains provisional and cannot mutate`() {
        val fixture = Fixture(platform = "C9300-24T", version = "17.18.2")

        val report = fixture.adapter().capabilityReport(fixture.target())
        val result = fixture.adapter().execute(fixture.target(), fixture.command())

        assertEquals(setOf("CERTIFICATION_PROVISIONAL", "IOS_XE_UNSUPPORTED_PROFILE"), report.capabilities)
        assertTrue(report.operationClasses.isEmpty())
        assertEquals(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY, result.errorCode)
        assertTrue(fixture.lifecycleEvents().isEmpty())
    }

    @Test
    fun `lock denial performs no edit and does not unlock an unowned lock`() {
        val fixture = Fixture(failure = Failure.LOCK)

        val result = fixture.adapter().execute(fixture.target(), fixture.command())

        assertEquals(ProvisioningErrorCode.MANUAL_RECONCILIATION, result.errorCode)
        assertEquals(listOf("LOCK_CANDIDATE"), fixture.lifecycleEvents())
    }

    @Test
    fun `validation failure discards candidate and unlocks without commit`() {
        val fixture = Fixture(failure = Failure.VALIDATE)

        val result = fixture.adapter().execute(fixture.target(), fixture.command())

        assertEquals(ProvisioningErrorCode.VALIDATION_FAILED, result.errorCode)
        assertEquals(
            listOf("LOCK_CANDIDATE", "DISCARD_CHANGES", "EDIT_CANDIDATE", "VALIDATE_CANDIDATE", "DISCARD_CHANGES", "UNLOCK_CANDIDATE"),
            fixture.lifecycleEvents(),
        )
        assertFalse("FINAL_COMMIT" in fixture.events)
    }

    @Test
    fun `verification timeout withholds confirmation and records timer rollback`() {
        val fixture = Fixture(failure = Failure.VERIFY_TIMEOUT)

        val result = fixture.adapter().execute(fixture.target(), fixture.command())

        assertEquals(ProvisioningErrorCode.TIMEOUT, result.errorCode)
        assertFalse("FINAL_COMMIT" in fixture.events)
        assertTrue("AWAIT_DEVICE_ROLLBACK:120" in fixture.events)
        assertEquals(true, result.rollback?.success)
        assertEquals(fixture.baseline.hash(), result.rollback?.resultingStateHash)
    }

    @Test
    fun `verification mismatch withholds confirmation and records timer rollback`() {
        val fixture = Fixture(failure = Failure.VERIFY_MISMATCH)

        val result = fixture.adapter().execute(fixture.target(), fixture.command())

        assertEquals(ProvisioningErrorCode.VERIFICATION_MISMATCH, result.errorCode)
        assertFalse("FINAL_COMMIT" in fixture.events)
        assertTrue("AWAIT_DEVICE_ROLLBACK:120" in fixture.events)
        assertEquals(false, result.verification?.matchesExpected)
        assertEquals(true, result.rollback?.success)
    }

    @Test
    fun `disconnect before confirmation reconnects only to observe device timed rollback`() {
        val fixture = Fixture(failure = Failure.VERIFY_DISCONNECT)

        val result = fixture.adapter().execute(fixture.target(), fixture.command())

        assertEquals(ProvisioningErrorCode.TIMEOUT, result.errorCode)
        assertEquals(2, fixture.events.count { it == "OPEN_SSH_NETCONF" })
        assertFalse("FINAL_COMMIT" in fixture.events)
        assertTrue("AWAIT_DEVICE_ROLLBACK:120" in fixture.events)
        assertEquals(true, result.rollback?.success)
    }

    private enum class Failure { NONE, LOCK, VALIDATE, VERIFY_TIMEOUT, VERIFY_MISMATCH, VERIFY_DISCONNECT }

    private class Fixture(
        private val platform: String = "C9300-24T",
        private val version: String = "17.18.1",
        private val capabilities: Set<String> = IosXeCapabilities.PROTOCOL,
        private val modules: Set<String> = IosXeProfiles.CATALYST_9300_17_18.requiredModules,
        private val failure: Failure = Failure.NONE,
    ) {
        val events = mutableListOf<String>()
        val baseline = IosXeOperationalState(
            vlanPresent = false,
            vlanId = null,
            trunkInterfaces = emptySet(),
            accessInterfaces = emptySet(),
            aclApplied = false,
            managementReachable = true,
        )
        private val desired = IosXeOperationalState(
            vlanPresent = true,
            vlanId = 110,
            trunkInterfaces = setOf("GigabitEthernet1/0/48"),
            accessInterfaces = emptySet(),
            aclApplied = true,
            managementReachable = true,
        )
        private var opens = 0

        fun adapter() = IosXeProvisioningAdapter(
            sessionFactory = { _, _ ->
                events += "OPEN_SSH_NETCONF"
                opens += 1
                FakeSession(events, platform, version, capabilities, modules, failure, baseline, desired, opens)
            },
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            confirmTimeoutSeconds = 120,
        )

        fun target() = NasTarget(
            nasId = "iosxe-1",
            name = "iosxe-1",
            vendor = "CISCO",
            host = "192.0.2.10",
            adapterType = "IOS_XE_NETCONF",
            apiUsername = "netconf-user",
            apiSecret = "netconf-password",
            apiPort = 830,
        )

        fun command() = ProvisioningPlanStepCommand(
            planId = "plan-7",
            revision = 1,
            stepId = "step-7",
            attemptId = "attempt-7",
            phase = ProvisioningCommandPhase.APPLY,
            operationClass = "ENSURE_TAGGED_VLAN",
            idempotencyKey = "task-7",
            fencingEpoch = 7,
            expectedPreconditionHash = baseline.hash(),
            deadline = NOW.plusSeconds(300),
            target = ProvisioningTarget("iosxe-1", "SWITCH", "192.0.2.10", "NETCONF_SSH"),
            payload = ProvisioningPayload(
                mapOf(
                    "tenantId" to "tenant-1",
                    "intentId" to "intent-1",
                    "vlanId" to "110",
                    "tagging" to "SINGLE_TAG",
                    "trunkPorts" to "GigabitEthernet1/0/48",
                    "firewallChain" to "FTTH-IN",
                ),
            ),
        )

        fun lifecycleEvents() = events.filterNot { it in setOf("OPEN_SSH_NETCONF", "HELLO", "READ_BASELINE", "CLOSE", "AWAIT_DEVICE_ROLLBACK:120") }
    }

    private class FakeSession(
        private val events: MutableList<String>,
        private val platform: String,
        private val version: String,
        private val capabilities: Set<String>,
        private val modules: Set<String>,
        private val failure: Failure,
        private val baseline: IosXeOperationalState,
        private val desired: IosXeOperationalState,
        private val openNumber: Int,
    ) : IosXeNetconfSession {
        override fun hello(): IosXeHello {
            events += "HELLO"
            return IosXeHello("Cisco IOS XE", platform, version, capabilities, modules)
        }

        override fun readBaseline(): IosXeOperationalState {
            events += "READ_BASELINE"
            return baseline
        }

        override fun lockCandidate() {
            events += "LOCK_CANDIDATE"
            if (failure == Failure.LOCK) throw IosXeNetconfException(IosXeNetconfError.LOCK_DENIED)
        }

        override fun discardChanges() { events += "DISCARD_CHANGES" }
        override fun editCandidate(xml: String) { events += "EDIT_CANDIDATE" }

        override fun validateCandidate() {
            events += "VALIDATE_CANDIDATE"
            if (failure == Failure.VALIDATE) throw IosXeNetconfException(IosXeNetconfError.VALIDATION)
        }

        override fun confirmedCommit(timeoutSeconds: Int) { events += "CONFIRMED_COMMIT:$timeoutSeconds" }

        override fun verifyOperational(expected: IosXeDesiredConfiguration): IosXeOperationalState {
            events += "VERIFY_OPERATIONAL"
            if (failure == Failure.VERIFY_TIMEOUT) throw IosXeNetconfException(IosXeNetconfError.TIMEOUT)
            if (failure == Failure.VERIFY_DISCONNECT) throw IOException("connection lost")
            return if (failure == Failure.VERIFY_MISMATCH) desired.copy(aclApplied = false) else desired
        }

        override fun finalCommit() { events += "FINAL_COMMIT" }
        override fun unlockCandidate() { events += "UNLOCK_CANDIDATE" }

        override fun awaitDeviceRollback(expectedStateHash: String, timeoutSeconds: Int): IosXeOperationalState {
            check(openNumber == 2)
            events += "AWAIT_DEVICE_ROLLBACK:$timeoutSeconds"
            return baseline
        }

        override fun close() { events += "CLOSE" }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-02T12:00:00Z")
    }
}
