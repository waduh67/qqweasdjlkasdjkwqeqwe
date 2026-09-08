package com.duluin.ftth.monitoring

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.monitoring.application.port.outbound.OltSnmpProbePort
import com.duluin.ftth.monitoring.application.port.outbound.SnmpGreeting
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeTarget
import com.duluin.ftth.monitoring.application.port.outbound.SnmpSample
import com.duluin.ftth.monitoring.application.service.OltOnuInventoryService
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OltPollingTarget
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.HsgqSnmpAdapter
import com.duluin.ftth.snmp.OidRole
import com.duluin.ftth.snmp.OltAdapter
import com.duluin.ftth.snmp.SnmpReaderFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

class OltOnuInventoryServiceTest {
    private val oltId = UUID.randomUUID()
    private val olt = OltPollingTarget(oltId, "OLT-01", "HSGQ", "192.0.2.10", "test-secret", 1161)
    private val network = mock(NetworkApi::class.java)
    private val adapter = HsgqSnmpAdapter(SnmpReaderFactory { _, _, _ ->
        error("Inventory must use the probe port, never the polling adapter")
    })

    @Test
    fun `snapshot uses serial identities without customer or discovered ONU data`() {
        val probe = FakeProbe(completeSamples())
        val before = Instant.now()

        val result = service(probe).read(oltId)

        assertThat(result.oltId).isEqualTo(oltId)
        assertThat(result.oltCode).isEqualTo("OLT-01")
        assertThat(result.vendor).isEqualTo("HSGQ")
        assertThat(result.systemDescription).isEqualTo("HSGQ-G01ID")
        assertThat(result.readAt).isBetween(before, Instant.now())
        assertThat(result.onus.map { it.serialNumber }).containsExactly("TEST001122AA", "TEST001122BB")
        val first = result.onus.first()
        assertThat(first.index).isEqualTo(INDEX)
        assertThat(first.ontId).isEqualTo("PON01/0")
        assertThat(first.name).isEqualTo("ONU01/00")
        assertThat(first.state).isEqualTo("Active")
        assertThat(first.runningState).isEqualTo("ONLINE")
        assertThat(first.configState).isEqualTo("Normal")
        assertThat(first.rxPowerDbm).isEqualTo(-22.0)
        assertThat(first.lastUpTime).isEqualTo("2026-09-08 09:10:11")
        assertThat(first.deviceType).isNull()
        assertThat(first.lastDownTime).isNull()
        assertThat(first.lastDownCause).isNull()
        assertThat(result.onus.last().runningState).isEqualTo("OFFLINE")
        assertThat(result.onus.last().rxPowerDbm).isNull()
        assertThat(result.warnings.joinToString()).contains("DEVICE_TYPE", "LAST_OFF", "DOWN_CAUSE", "RX_POWER")
        assertThat(probe.targets).containsOnly(SnmpProbeTarget("192.0.2.10", 1161, "test-secret"))
        assertThat(probe.walkCalls.first()).containsExactly(SERIAL)
        assertThat(probe.walkCalls).hasSize(3)
        assertThat(probe.walkCalls[1]).contains(NAME, STATUS, STATE, CONFIG, LAST_ON).doesNotContain(SERIAL, RX)
        assertThat(probe.walkCalls[2]).containsExactly(RX)
        verify(network).findPollingTargets(setOf(oltId))
        verifyNoMoreInteractions(network)
    }

    @Test
    fun `zero identity index survives and OLT optical suffix cannot replace ONU power`() {
        val probe = FakeProbe(
            mapOf(
                SERIAL to listOf(SnmpSample("0", "TEST001122AA")),
                RX to listOf(SnmpSample("0.0.0", "-2200"), SnmpSample("0.65535.65535", "-1000")),
            ),
        )

        val row = service(probe).read(oltId).onus.single()

        assertThat(row.index).isEqualTo("0")
        assertThat(row.rxPowerDbm).isEqualTo(-22.0)
    }

    @Test
    fun `tenant missing ID returns not found before any SNMP`() {
        val probe = FakeProbe()

        assertThatThrownBy { service(probe, target = null).read(oltId) }
            .isInstanceOf(NotFoundException::class.java)

        assertThat(probe.targets).isEmpty()
        verify(network).findPollingTargets(setOf(oltId))
        verifyNoMoreInteractions(network)
    }

    @Test
    fun `missing configuration and unsupported vendor fail before SNMP`() {
        val probe = FakeProbe()
        val targets = listOf(olt.copy(host = null), olt.copy(snmpCommunity = " "), olt.copy(vendor = "UNSUPPORTED"))

        targets.forEach { target ->
            assertThatThrownBy { service(probe, target).read(oltId) }
                .isInstanceOf(ValidationException::class.java)
        }

        assertThat(probe.targets).isEmpty()
    }

    @Test
    fun `optical failure preserves identities and independently read metadata`() {
        val probe = FakeProbe(completeSamples(), failedOids = setOf(RX))

        val result = service(probe).read(oltId)

        assertThat(result.onus).hasSize(2)
        assertThat(result.onus.first().name).isEqualTo("ONU01/00")
        assertThat(result.onus.first().runningState).isEqualTo("ONLINE")
        assertThat(result.onus.map { it.rxPowerDbm }).containsOnlyNulls()
        assertThat(result.warnings.joinToString()).contains("RX_POWER").doesNotContain("test-secret")
    }

    @Test
    fun `metadata failure preserves identities and independent optical readings`() {
        val probe = FakeProbe(completeSamples(), failedOids = setOf(NAME))

        val result = service(probe).read(oltId)

        assertThat(result.onus).hasSize(2)
        assertThat(result.onus.map { it.name }).containsOnlyNulls()
        assertThat(result.onus.map { it.runningState }).containsOnlyNulls()
        assertThat(result.onus.first().rxPowerDbm).isEqualTo(-22.0)
        assertThat(result.warnings.joinToString()).contains("NAME", "STATUS").doesNotContain("test-secret")
    }

    @Test
    fun `identity walk failure is an error not a partial or empty inventory`() {
        val probe = FakeProbe(completeSamples(), failedOids = setOf(SERIAL))

        assertThatThrownBy { service(probe).read(oltId) }
            .isInstanceOf(SnmpProbeFailure::class.java)
            .hasMessageContaining("SERIAL")
            .hasMessageNotContaining("test-secret")

        assertThat(probe.walkCalls).containsExactly(listOf(SERIAL))
    }

    @Test
    fun `greeting failure does not start an identity walk`() {
        val probe = FakeProbe(greetFailure = true)

        assertThatThrownBy { service(probe).read(oltId) }
            .isInstanceOf(SnmpProbeFailure::class.java)
            .hasMessageNotContaining("test-secret")

        assertThat(probe.walkCalls).isEmpty()
    }

    @Test
    fun `absent SERIAL result is an incomplete snapshot error`() {
        val probe = FakeProbe(omittedOids = setOf(SERIAL))

        assertThatThrownBy { service(probe).read(oltId) }
            .isInstanceOf(SnmpProbeFailure::class.java)

        assertThat(probe.walkCalls).containsExactly(listOf(SERIAL))
    }

    @Test
    fun `empty SERIAL includes a physical inventory caveat and skips optional reads`() {
        val probe = FakeProbe()

        val result = service(probe).read(oltId)

        assertThat(result.onus).isEmpty()
        assertThat(result.warnings.joinToString()).contains("SERIAL", "fisik")
        assertThat(probe.walkCalls).containsExactly(listOf(SERIAL))
    }

    @Test
    fun `malformed and unknown serials warn instead of inventing identities`() {
        val probe = FakeProbe(
            mapOf(SERIAL to listOf(
                SnmpSample(INDEX, "TEST001122AA"),
                SnmpSample("1", "UNKNOWN"),
                SnmpSample("2", "bad-serial"),
                SnmpSample("3", " "),
            )),
        )

        val result = service(probe).read(oltId)

        assertThat(result.onus.map { it.serialNumber }).containsExactly("TEST001122AA")
        assertThat(result.warnings.joinToString()).contains("SERIAL", "1", "2", "3")
    }

    @Test
    fun `unrecognized and missing optional values stay null with warnings`() {
        val samples = completeSamples() + mapOf(
            STATE to listOf(SnmpSample(INDEX, "99")),
            STATUS to listOf(SnmpSample(INDEX, "0")),
            CONFIG to listOf(SnmpSample(INDEX, "99")),
            NAME to listOf(SnmpSample(INDEX, " ")),
            RX to listOf(SnmpSample("$INDEX.0.0", "-2147483648")),
        )

        val result = service(FakeProbe(samples, omittedOids = setOf(LAST_ON))).read(oltId)

        val row = result.onus.first()
        assertThat(row.name).isNull()
        assertThat(row.state).isNull()
        assertThat(row.runningState).isNull()
        assertThat(row.configState).isNull()
        assertThat(row.rxPowerDbm).isNull()
        assertThat(row.lastUpTime).isNull()
        assertThat(result.warnings.joinToString()).contains("NAME", "STATE", "STATUS", "CONFIG_STATE", "RX_POWER", "LAST_ON")
    }

    @Test
    fun `adapter normalized power must be a finite number before reaching JSON`() {
        val invalidPowerAdapter = object : OltAdapter by adapter {
            override fun oidPlanFor(systemDescription: String?): List<OidRole> =
                adapter.oidPlanFor(systemDescription).map { role ->
                    if (role.role == "RX_POWER") role.copy(interpret = { "NaN dBm" }) else role
                }
        }

        val result = service(FakeProbe(completeSamples()), selectedAdapter = invalidPowerAdapter).read(oltId)

        assertThat(result.onus.map { it.rxPowerDbm }).containsOnlyNulls()
        assertThat(result.warnings.joinToString()).contains("RX_POWER")
    }

    private fun service(
        probe: OltSnmpProbePort,
        target: OltPollingTarget? = olt,
        selectedAdapter: OltAdapter = adapter,
    ): OltOnuInventoryService {
        `when`(network.findPollingTargets(setOf(oltId))).thenReturn(listOfNotNull(target))
        return OltOnuInventoryService(network, AdapterRegistry(listOf(selectedAdapter)), probe)
    }

    private fun completeSamples() = mapOf(
        SERIAL to listOf(SnmpSample(INDEX, "TEST001122aa"), SnmpSample("16777479", "TEST001122BB")),
        NAME to listOf(SnmpSample(INDEX, "ONU01/00")),
        STATE to listOf(SnmpSample(INDEX, "1")),
        STATUS to listOf(SnmpSample(INDEX, "1"), SnmpSample("16777479", "2")),
        CONFIG to listOf(SnmpSample(INDEX, "1")),
        LAST_ON to listOf(SnmpSample(INDEX, "2026-09-08 09:10:11")),
        RX to listOf(
            SnmpSample("$INDEX.0.0", "-2200"),
            SnmpSample("$INDEX.65535.65535", "-1000"),
            SnmpSample("16777480.0.0", "-1800"),
        ),
    )

    private class FakeProbe(
        private val samples: Map<String, List<SnmpSample>> = emptyMap(),
        private val failedOids: Set<String> = emptySet(),
        private val omittedOids: Set<String> = emptySet(),
        private val greetFailure: Boolean = false,
    ) : OltSnmpProbePort {
        val targets = mutableListOf<SnmpProbeTarget>()
        val walkCalls = mutableListOf<List<String>>()

        override fun greet(target: SnmpProbeTarget): SnmpGreeting {
            targets += target
            if (greetFailure) throw SnmpProbeFailure("timeout test-secret")
            return SnmpGreeting("HSGQ-G01ID", 1)
        }

        override fun walk(target: SnmpProbeTarget, rootOids: List<String>): Map<String, List<SnmpSample>> {
            targets += target
            walkCalls += rootOids
            if (rootOids.any { it in failedOids }) throw SnmpProbeFailure("timeout test-secret")
            return rootOids.filterNot { it in omittedOids }.associateWith { samples[it].orEmpty() }
        }
    }

    private companion object {
        const val INDEX = "16777472"
        const val SERIAL = "1.3.6.1.4.1.50224.3.12.2.1.15"
        const val NAME = "1.3.6.1.4.1.50224.3.12.2.1.2"
        const val STATE = "1.3.6.1.4.1.50224.3.12.2.1.3"
        const val STATUS = "1.3.6.1.4.1.50224.3.12.2.1.4"
        const val CONFIG = "1.3.6.1.4.1.50224.3.12.2.1.5"
        const val LAST_ON = "1.3.6.1.4.1.50224.3.12.2.1.20"
        const val RX = "1.3.6.1.4.1.50224.3.12.3.1.4"
    }
}
