package com.karuhun.ftth.monitoring

import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.monitoring.application.port.outbound.*
import com.karuhun.ftth.monitoring.application.service.PonPortDiscoveryService
import com.karuhun.ftth.network.NetworkApi
import com.karuhun.ftth.network.OltPollingTarget
import com.karuhun.ftth.snmp.AdapterRegistry
import com.karuhun.ftth.snmp.HsgqSnmpAdapter
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.UUID

class PonPortDiscoveryServiceTest {
    private val id = UUID.randomUUID()
    private val network = mock(NetworkApi::class.java)
    private val target = OltPollingTarget(id, "OLT-01", "HSGQ", "192.0.2.1", "private-community", 1161)
    private val serial = HsgqSnmpAdapter.SERIAL_OID
    private val names = PonPortDiscoveryService.IF_NAME
    private val description = PonPortDiscoveryService.IF_DESCR

    private class Probe : OltSnmpProbePort {
        val values = mutableMapOf<String, List<SnmpSample>>()
        val failures = mutableSetOf<String>()
        var calls = 0
        override fun greet(target: SnmpProbeTarget): SnmpGreeting { calls++; return SnmpGreeting("HSGQ-G01ID", 1) }
        override fun walk(target: SnmpProbeTarget, rootOids: List<String>): Map<String, List<SnmpSample>> {
            calls++
            if (rootOids.any { it in failures }) throw SnmpProbeFailure("transport failed")
            return rootOids.associateWith { values[it].orEmpty() }
        }
    }
    private fun service(probe: Probe, olt: OltPollingTarget? = target): PonPortDiscoveryService {
        `when`(network.findPollingTarget(id)).thenReturn(olt)
        return PonPortDiscoveryService(network, AdapterRegistry(listOf(HsgqSnmpAdapter())), probe)
    }
    @Test fun `joins physical interfaces and ONU identities without counting duplicates or accepting uplinks`() {
        val probe = Probe().apply {
            values[names] = listOf("GPON01", "PON2", "EPON3", "GE1", "PON1/ONU1", "GPON0/1:1").mapIndexed { i, s -> SnmpSample(i.toString(), s) }
            values[description] = listOf(SnmpSample("1", "PON 1"))
            values[serial] = listOf(SnmpSample("16777472", "TEST00000001"), SnmpSample("16777473", "TEST00000002"),
                SnmpSample("16777473.0.0", "TEST00000002"), SnmpSample("16777728", "TEST00000003"))
        }
        val result = service(probe).discover(id)
        assertThat(result.ports.map { it.label }).containsExactly("PON1", "PON2", "PON3")
        assertThat(result.ports.map { it.onuCount }).containsExactly(2, 1, 0)
        assertThat(result.ports.all { it.fromInterface }).isTrue()
        assertThat(result.warnings).isEmpty()
        assertThat(result.toString()).doesNotContain("private-community")
    }
    @Test fun `falls back to populated ports when interfaces are unavailable`() {
        val probe = Probe().apply { failures.add(names); values[serial] = listOf(SnmpSample("16777472", "TEST00000001")) }
        val result = service(probe).discover(id)
        assertThat(result.ports.single().label).isEqualTo("PON1")
        assertThat(result.ports.single().fromInterface).isFalse()
        assertThat(result.warnings).contains("Port tanpa ONU mungkin belum terdeteksi.")
    }
    @Test fun `keeps a physical port with unknown count when the ONU walk fails`() {
        val probe = Probe().apply { failures.add(serial); values[names] = listOf(SnmpSample("1", "PON4")) }
        val result = service(probe).discover(id)
        assertThat(result.ports.single().label).isEqualTo("PON4")
        assertThat(result.ports.single().onuCount).isNull()
        assertThat(result.warnings).contains("Jumlah ONU belum tersedia.")
    }
    @Test fun `failed discovery cannot masquerade as an empty OLT`() {
        val probe = Probe().apply { failures.addAll(listOf(serial, names)) }
        assertThatThrownBy { service(probe).discover(id) }.isInstanceOf(SnmpProbeFailure::class.java)
    }
    @Test fun `ignores invalid serials and OLT optical suffixes`() {
        val probe = Probe().apply { values[serial] = listOf(SnmpSample("16777472.65535.65535", "TEST00000001"), SnmpSample("16777472", "UNKNOWN")) }
        val result = service(probe).discover(id)
        assertThat(result.ports).isEmpty()
        assertThat(result.warnings).contains("Sebagian data ONU belum dikenali.")
    }
    @Test fun `tenant scope and readiness are checked before sending SNMP requests`() {
        val probe = Probe()
        assertThatThrownBy { service(probe, null).discover(id) }.isInstanceOf(NotFoundException::class.java)
        for (olt in listOf(target.copy(snmpEnabled = false), target.copy(active = false), target.copy(host = null), target.copy(vendor = "OTHER"))) {
            assertThatThrownBy { service(probe, olt).discover(id) }.isInstanceOf(ValidationException::class.java)
        }
        assertThat(probe.calls).isZero()
    }
}
