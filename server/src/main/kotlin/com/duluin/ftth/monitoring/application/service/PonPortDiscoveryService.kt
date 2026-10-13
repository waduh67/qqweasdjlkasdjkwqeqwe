package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.monitoring.application.port.inbound.DetectedPonPort
import com.duluin.ftth.monitoring.application.port.inbound.DiscoverPonPortsUseCase
import com.duluin.ftth.monitoring.application.port.inbound.PonPortDiscovery
import com.duluin.ftth.monitoring.application.port.outbound.OltSnmpProbePort
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeTarget
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OltPollingReadiness
import com.duluin.ftth.snmp.AdapterRegistry
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Discovery reads the device without creating inventory or holding a DB transaction. */
@Service
class PonPortDiscoveryService(
    private val network: NetworkApi,
    private val adapters: AdapterRegistry,
    private val probe: OltSnmpProbePort,
) : DiscoverPonPortsUseCase {
    override fun discover(oltId: UUID): PonPortDiscovery {
        val olt = network.findPollingTarget(oltId) ?: throw NotFoundException("OLT tidak ditemukan.")
        val adapter = adapters.forVendor(olt.vendor)
        if (adapter?.supportsPonDiscovery != true) {
            throw ValidationException("Deteksi port belum tersedia untuk vendor ini. Tambahkan port secara manual.")
        }
        if (olt.pollingReadiness() != OltPollingReadiness.READY) {
            throw ValidationException("Aktifkan OLT dan lengkapi pengaturan SNMP sebelum mendeteksi port.")
        }
        val target = SnmpProbeTarget(requireNotNull(olt.host), olt.snmpPort, requireNotNull(olt.snmpCommunity))
        val greeting = try { probe.greet(target) }
        catch (ex: SnmpProbeFailure) { throw SnmpProbeFailure("OLT tidak dapat dihubungi. Periksa koneksi dan pengaturan SNMP.", ex) }
        val serialRole = adapter.oidPlanFor(greeting.systemDescription).firstOrNull { it.role == "SERIAL" }
        val interfaces = try { probe.walk(target, listOf(IF_NAME, IF_DESCR)) }
        catch (_: SnmpProbeFailure) { emptyMap() }
        val labels = interfaces.values.flatten().mapNotNull { adapter.ponLabelFromInterface(it.value) }.toSet()
        val serials = serialRole?.oid?.let { oid ->
            try { probe.walk(target, listOf(oid))[oid] }
            catch (_: SnmpProbeFailure) { null }
        }
        if (labels.isEmpty() && serials == null) throw SnmpProbeFailure("Port PON belum dapat dibaca. Coba lagi.")
        val onus = linkedMapOf<String, MutableSet<String>>()
        var unrecognized = false
        serials.orEmpty().forEach { sample ->
            val label = adapter.ponLabelFromOnuIndex(sample.index)
            val serial = serialRole?.interpret?.invoke(sample.value)?.takeIf { it.isNotBlank() }
            if (label != null && serial != null) onus.getOrPut(label) { linkedSetOf() }.add(serial)
            else unrecognized = true
        }
        val warnings = buildList {
            if (labels.isEmpty()) add("Port tanpa ONU mungkin belum terdeteksi.")
            if (serials == null) add("Jumlah ONU belum tersedia.")
            if (unrecognized) add("Sebagian data ONU belum dikenali.")
        }
        val ports = (labels + onus.keys).sortedBy { it.removePrefix("PON").toIntOrNull() ?: Int.MAX_VALUE }.map {
            DetectedPonPort(it, if (serials == null) null else onus[it]?.size ?: 0, it in labels)
        }
        return PonPortDiscovery(olt.id, Instant.now(), ports, warnings)
    }

    companion object {
        const val IF_NAME = "1.3.6.1.2.1.31.1.1.1.1"
        const val IF_DESCR = "1.3.6.1.2.1.2.2.1.2"
    }
}
