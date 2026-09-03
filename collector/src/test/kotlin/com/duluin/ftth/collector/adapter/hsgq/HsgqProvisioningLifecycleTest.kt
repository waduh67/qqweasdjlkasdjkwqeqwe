package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.ProvisioningCommandPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HsgqProvisioningLifecycleTest {
    @Test
    fun `default empty certification registry reports provisional supported boundary`() {
        val session = FixtureSession()
        val provisioner = adapter(session, certifications = emptyList())

        val report = provisioner.capabilityReport(target())

        assertEquals(setOf("CERTIFICATION_PROVISIONAL"), report.capabilities)
        assertEquals(HsgqOperation.supported, report.operationClasses)
    }

    @Test
    fun `discovery reports exact certified firmware and operations`() {
        val session = FixtureSession()

        val report = adapter(session).capabilityReport(target())

        assertEquals("HSGQ-E04I", report.fingerprint.model)
        assertEquals("V1.2.3-certified", report.fingerprint.firmware)
        assertEquals("HTTPS_API", report.fingerprint.transport)
        assertTrue("CERTIFIED:$EVIDENCE_SHA256" in report.capabilities)
        assertEquals(setOf("ENSURE_TAGGED_VLAN", "REMOVE_TAGGED_VLAN", "VERIFY_STATE"), report.operationClasses)
    }

    @Test
    fun `apply creates binding and uplink persists reconnects and verifies`() {
        val session = FixtureSession()
        val provisioner = adapter(session)
        val preflight = provisioner.execute(target(), command(ProvisioningCommandPhase.PREFLIGHT, expectedHash = null))

        val result = provisioner.execute(
            target(),
            command(ProvisioningCommandPhase.APPLY, expectedHash = preflight.preflight?.preconditionHash),
        )

        assertTrue(result.success)
        assertTrue(result.apply?.changed == true)
        assertTrue(result.verification?.matchesExpected == true)
        assertEquals(listOf("discover", "discover", "ensure-binding", "ensure-uplink:GE1", "persist", "reconnect", "discover"), session.calls)
        assertEquals(session.persisted, session.running)
    }

    @Test
    fun `verify reads state and confirms expected service`() {
        val session = FixtureSession(
            fixtureState(
                setOf(HsgqSubscriberVlanBinding(3901, "EPON1/1:ONU1")),
                setOf(HsgqTaggedUplinkMembership(3901, "GE1")),
            ),
        )

        val result = adapter(session).execute(
            target(),
            command(ProvisioningCommandPhase.VERIFY, operation = "VERIFY_STATE", expectedHash = null),
        )

        assertTrue(result.success)
        assertTrue(result.verification?.matchesExpected == true)
        assertEquals(listOf("discover"), session.calls)
    }

    @Test
    fun `exact delivery replay returns cached result without device access`() {
        val session = FixtureSession()
        val provisioner = adapter(session)
        val delivery = command(ProvisioningCommandPhase.PREFLIGHT, expectedHash = null)
        val first = provisioner.execute(target(), delivery)
        session.calls.clear()

        val replay = provisioner.execute(target(), delivery)

        assertEquals(first, replay)
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun `duplicate apply is a no-op`() {
        val desired = HsgqDesiredVlan(3901, "EPON1/1:ONU1", setOf("GE1"))
        val session = FixtureSession(
            fixtureState(
                setOf(HsgqSubscriberVlanBinding(desired.vlanId, desired.subscriberPort)),
                setOf(HsgqTaggedUplinkMembership(desired.vlanId, "GE1")),
            ),
        )
        val result = adapter(session).execute(target(), command(ProvisioningCommandPhase.APPLY, expectedHash = null))

        assertTrue(result.success)
        assertFalse(result.apply?.changed ?: true)
        assertEquals(listOf("discover"), session.calls)
    }

    @Test
    fun `remove deletes binding and uplink and survives reconnect`() {
        val session = FixtureSession(
            fixtureState(
                setOf(HsgqSubscriberVlanBinding(3901, "EPON1/1:ONU1")),
                setOf(HsgqTaggedUplinkMembership(3901, "GE1")),
            ),
        )

        val result = adapter(session).execute(
            target(),
            command(ProvisioningCommandPhase.APPLY, operation = "REMOVE_TAGGED_VLAN", expectedHash = null),
        )

        assertTrue(result.success)
        assertTrue(session.running.subscriberBindings.isEmpty())
        assertTrue(session.running.taggedUplinks.isEmpty())
        assertTrue("persist" in session.calls)
        assertTrue("reconnect" in session.calls)
    }

    @Test
    fun `rollback restores read-only baseline and persists it`() {
        val session = FixtureSession()
        val provisioner = adapter(session)
        val preflight = provisioner.execute(target(), command(ProvisioningCommandPhase.PREFLIGHT, expectedHash = null))
        provisioner.execute(target(), command(ProvisioningCommandPhase.APPLY, expectedHash = preflight.preflight?.preconditionHash))

        val result = provisioner.execute(target(), command(ProvisioningCommandPhase.ROLLBACK, expectedHash = null))

        assertTrue(result.success)
        assertEquals(fixtureState(), session.running)
        assertTrue(result.rollback?.success == true)
    }
}
