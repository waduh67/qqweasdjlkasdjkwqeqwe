package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.OltManagementTransport
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HsgqProvisioningFailureTest {
    @Test
    fun `timeout is transient and never cached`() {
        val session = FixtureSession(failure = HsgqTransportFailure(HsgqFailureStage.DISCOVERY, HsgqFailureKind.TIMEOUT))
        val provisioner = adapter(session)

        val first = provisioner.execute(target(), command(ProvisioningCommandPhase.APPLY, expectedHash = null))
        val second = provisioner.execute(target(), command(ProvisioningCommandPhase.APPLY, expectedHash = null))

        assertEquals(ProvisioningErrorCode.TIMEOUT, first.errorCode)
        assertEquals(ProvisioningErrorCode.TIMEOUT, second.errorCode)
        assertEquals(2, session.calls.count { it == "discover" })
    }

    @Test
    fun `authentication failure is explicit`() {
        val session = FixtureSession(failure = HsgqTransportFailure(HsgqFailureStage.DISCOVERY, HsgqFailureKind.AUTHENTICATION))

        val result = adapter(session).execute(target(), command(ProvisioningCommandPhase.PREFLIGHT, expectedHash = null))

        assertEquals(ProvisioningErrorCode.AUTHENTICATION_FAILED, result.errorCode)
    }

    @Test
    fun `changed firmware remains provisional and apply performs no write`() {
        val session = FixtureSession(fixtureState().copy(firmware = "V1.2.4-unknown"))
        val provisioner = adapter(session)

        val report = provisioner.capabilityReport(target())

        val result = provisioner.execute(
            target(firmware = "V1.2.4-unknown"),
            command(ProvisioningCommandPhase.APPLY, expectedHash = null),
        )

        assertEquals("V1.2.4-unknown", report.fingerprint.firmware)
        assertTrue("CERTIFICATION_PROVISIONAL" in report.capabilities)
        assertEquals(ProvisioningErrorCode.UNCERTIFIED_FINGERPRINT, result.errorCode)
        assertTrue(session.calls.none { it.startsWith("ensure") || it == "persist" })
    }

    @Test
    fun `telnet always requires manual handling`() {
        val session = FixtureSession()

        val result = adapter(session).execute(
            target(transport = OltManagementTransport.TELNET),
            command(ProvisioningCommandPhase.APPLY, expectedHash = null),
        )

        assertEquals(ProvisioningErrorCode.REQUIRES_MANUAL, result.errorCode)
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun `snapshot persistence failure happens before device mutation`() {
        val session = FixtureSession()
        val failingStore = object : HsgqProvisioningStateStore by InMemoryHsgqProvisioningStateStore() {
            override fun saveSnapshotIfAbsent(stepKey: String, snapshot: HsgqProvisioningSnapshot): HsgqProvisioningSnapshot {
                throw HsgqStatePersistenceException("fixture persistence failure")
            }
        }

        val result = adapter(session, store = failingStore).execute(
            target(),
            command(ProvisioningCommandPhase.APPLY, expectedHash = null),
        )

        assertEquals(ProvisioningErrorCode.PERSISTENCE_FAILED, result.errorCode)
        assertFalse(session.calls.any { it.startsWith("ensure") || it.startsWith("remove") })
    }

    @Test
    fun `device persistence failure restores baseline before returning`() {
        val session = FixtureSession(failure = HsgqTransportFailure(HsgqFailureStage.PERSISTENCE, HsgqFailureKind.PERSISTENCE))

        val result = adapter(session).execute(target(), command(ProvisioningCommandPhase.APPLY, expectedHash = null))

        assertEquals(ProvisioningErrorCode.PERSISTENCE_FAILED, result.errorCode)
        assertEquals(fixtureState(), session.running)
        assertTrue("restore" in session.calls)
    }
}
