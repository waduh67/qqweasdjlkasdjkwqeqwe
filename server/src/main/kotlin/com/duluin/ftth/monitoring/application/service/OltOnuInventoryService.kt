package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.monitoring.application.port.inbound.OltOnuInventory
import com.duluin.ftth.monitoring.application.port.inbound.OltOnuInventoryRow
import com.duluin.ftth.monitoring.application.port.inbound.OltOnuInventoryUseCase
import com.duluin.ftth.monitoring.application.port.outbound.OltSnmpProbePort
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeTarget
import com.duluin.ftth.monitoring.application.port.outbound.SnmpSample
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.OidRole
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class OltOnuInventoryService(
    private val networkApi: NetworkApi,
    private val adapterRegistry: AdapterRegistry,
    private val probe: OltSnmpProbePort,
) : OltOnuInventoryUseCase {
    override fun read(oltId: UUID): OltOnuInventory {
        val olt = networkApi.findPollingTargets(setOf(oltId)).firstOrNull { it.id == oltId }
            ?: throw NotFoundException("OLT tidak ditemukan")
        val host = olt.host?.takeIf(String::isNotBlank)
            ?: throw ValidationException("OLT ${olt.code} belum punya alamat manajemen")
        val community = olt.snmpCommunity?.takeIf(String::isNotBlank)
            ?: throw ValidationException("OLT ${olt.code} belum punya community string SNMP")
        val adapter = adapterRegistry.forVendor(olt.vendor)
            ?: throw ValidationException("Vendor ${olt.vendor} belum punya adapter SNMP")
        val target = SnmpProbeTarget(host, olt.snmpPort, community)
        val greeting = try {
            probe.greet(target)
        } catch (ex: SnmpProbeFailure) {
            throw SnmpProbeFailure("OLT ${olt.code} gagal dihubungi melalui SNMP; inventori tidak dapat dibaca", ex)
        }
        val roles = adapter.oidPlanFor(greeting.systemDescription).associateBy { it.role }
        val serialRole = roles["SERIAL"]
            ?: throw ValidationException("Profil ${olt.vendor} belum memetakan SERIAL untuk inventori ONU")
        val serialOid = serialRole.oid
            ?: throw ValidationException("Profil ${olt.vendor} belum memetakan OID SERIAL untuk inventori ONU")

        // SERIAL harus selesai sendiri: kegagalan metadata/optik tidak boleh menghapus identitas.
        val serialSamples = try {
            probe.walk(target, listOf(serialOid))[serialOid]
                ?: throw SnmpProbeFailure("Hasil SERIAL tidak lengkap")
        } catch (ex: SnmpProbeFailure) {
            throw SnmpProbeFailure("Pembacaan SERIAL OLT ${olt.code} gagal; inventori tidak dapat dipastikan", ex)
        }
        val warnings = mutableListOf<String>()
        if (greeting.systemDescription.isNullOrBlank()) {
            warnings += "sysDescr tidak tersedia; profil SNMP memakai pilihan baku vendor"
        }
        val serials = linkedMapOf<String, String>()
        serialSamples.forEach { sample ->
            val index = adapter.inventoryIndex(sample.index)
            val serial = serialRole.interpret(sample.value).knownValue()
            if (index.isNullOrBlank() || serial == null) {
                warnings += "SERIAL pada indeks ${sample.index} tidak dikenali; baris diabaikan"
            } else if (serials.putIfAbsent(index, serial) != null) {
                warnings += "SERIAL pada indeks $index berulang; hanya identitas pertama dipakai"
            }
        }
        if (serials.isEmpty()) {
            warnings += "OID SERIAL kosong atau tidak menghasilkan identitas yang terbaca; ini bukan bukti tidak ada ONU fisik pada OLT"
            return OltOnuInventory(olt.id, olt.code, olt.vendor, greeting.systemDescription, Instant.now(), emptyList(), warnings)
        }

        val optionalRoles = OPTIONAL_ROLES.mapNotNull(roles::get)
        val samples = readOptional(target, optionalRoles.filter { it.role != "RX_POWER" }, warnings) +
            readOptional(target, optionalRoles.filter { it.role == "RX_POWER" }, warnings)
        val values = OPTIONAL_ROLES.associateWith { code ->
            val role = roles[code]
            if (role?.oid == null) {
                warnings += "$code belum dipetakan pada profil ${olt.vendor}; nilai null"
                emptyMap()
            } else {
                val byIndex = samples[role.oid].orEmpty().mapNotNull { sample ->
                    val index = adapter.inventoryIndex(sample.index) ?: return@mapNotNull null
                    if (index !in serials) return@mapNotNull null
                    val interpreted = role.interpret(sample.value).knownValue()
                    val value = if (code == "RX_POWER") {
                        interpreted?.removeSuffix(" dBm")?.toDoubleOrNull()?.takeIf { it.isFinite() }?.toString()
                    } else interpreted
                    value?.let { index to it }
                }.toMap()
                val missing = serials.keys.count { it !in byIndex }
                if (missing > 0) {
                    warnings += "$code tidak tersedia atau tidak dikenali untuk $missing ONU; nilai null"
                }
                byIndex
            }
        }
        val onus = serials.map { (index, serial) ->
            val ontId = adapter.ontIdFrom(index).knownValue()
            if (ontId == null) warnings += "ONT ID untuk indeks $index tidak dikenali; nilai null"
            OltOnuInventoryRow(
                index = index,
                ontId = ontId,
                name = values["NAME"]?.get(index),
                serialNumber = serial,
                state = values["STATE"]?.get(index),
                runningState = values["STATUS"]?.get(index),
                configState = values["CONFIG_STATE"]?.get(index),
                deviceType = values["DEVICE_TYPE"]?.get(index),
                rxPowerDbm = values["RX_POWER"]?.get(index)?.toDoubleOrNull(),
                lastUpTime = values["LAST_ON"]?.get(index),
                lastDownTime = values["LAST_OFF"]?.get(index),
                lastDownCause = values["DOWN_CAUSE"]?.get(index),
            )
        }
        return OltOnuInventory(olt.id, olt.code, olt.vendor, greeting.systemDescription, Instant.now(), onus, warnings)
    }

    private fun readOptional(
        target: SnmpProbeTarget,
        roles: List<OidRole>,
        warnings: MutableList<String>,
    ): Map<String, List<SnmpSample>> {
        val oids = roles.mapNotNull { it.oid }.distinct()
        if (oids.isEmpty()) return emptyMap()
        return try {
            probe.walk(target, oids)
        } catch (ex: SnmpProbeFailure) {
            val codes = roles.filter { it.oid != null }.joinToString { it.role }
            warnings += "Pembacaan opsional $codes gagal; nilai null, inventori SERIAL tetap ditampilkan"
            emptyMap()
        }
    }

    private fun String?.knownValue(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("UNKNOWN", ignoreCase = true) }

    private companion object {
        val OPTIONAL_ROLES = listOf(
            "NAME", "STATE", "STATUS", "CONFIG_STATE", "DEVICE_TYPE",
            "RX_POWER", "LAST_ON", "LAST_OFF", "DOWN_CAUSE",
        )
    }
}
