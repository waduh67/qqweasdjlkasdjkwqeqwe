package com.duluin.ftth.incident.application.service

import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.incident.application.port.inbound.IncidentAlarm
import com.duluin.ftth.incident.application.port.inbound.IncidentQuery
import com.duluin.ftth.incident.application.port.inbound.IncidentView
import com.duluin.ftth.monitoring.AlarmImpact
import com.duluin.ftth.monitoring.MonitoringApi
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.UpstreamPath
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Mesin korelasi alarm → insiden.
 *
 * Aturannya menaiki pohon topologi mencari akar bersama:
 *
 * - Alarm perangkat (OLT/ODC/ODP) langsung menjadi akar insidennya sendiri.
 * - Alarm ONU dinaikkan ke hulu: bila OLT-nya juga beralarm, seluruhnya melebur
 *   ke insiden OLT itu (satu OLT modar = satu insiden, bukan ratusan). Bila ODC-nya
 *   beralarm, melebur ke ODC. Bila tak ada alarm perangkat di atasnya tapi **dua
 *   ONU atau lebih** di bawah satu ODC bermasalah, ODC itu jadi tersangka akar
 *   bersama. ONU tunggal yang terisolasi berdiri sebagai insidennya sendiri —
 *   itu memang gangguan per-pelanggan (drop/ONU-nya), bukan gangguan ODC.
 *
 * Semuanya lewat kontrak publik module lain; `incident` tidak menyentuh tabel
 * mana pun secara langsung.
 */
@Service
@Transactional(readOnly = true)
class IncidentCorrelationService(
    private val monitoringApi: MonitoringApi,
    private val networkApi: NetworkApi,
    private val customerApi: CustomerApi,
) : IncidentQuery {

    private data class Root(val type: String, val id: UUID, val label: String)
    private class Bucket(val root: Root) {
        val members = mutableListOf<AlarmImpact>()
        val customers = HashSet<UUID>()
    }

    override fun activeIncidents(): List<IncidentView> {
        val impacts = monitoringApi.activeImpacts()
        if (impacts.isEmpty()) return emptyList()

        val alarmingDeviceIds = impacts
            .filter { it.entityType == "OLT" || it.entityType == "ODC" || it.entityType == "ODP" }
            .mapTo(HashSet()) { it.entityId }

        // ONU → penempatan (pelanggan, ODP) lalu ODP → hulu, di-cache per ODP agar
        // banyak ONU di bawah ODP yang sama tidak memanggil telusur berulang kali.
        val onuImpacts = impacts.filter { it.entityType == "ONU" }
        val placementByOnu = if (onuImpacts.isEmpty()) emptyMap()
        else customerApi.placementsForOnus(onuImpacts.mapTo(HashSet()) { it.entityId }).associateBy { it.onuId }
        val upstreamByOdp = HashMap<UUID, UpstreamPath>()

        val buckets = LinkedHashMap<String, Bucket>()
        fun bucket(root: Root) = buckets.getOrPut("${root.type}:${root.id}") { Bucket(root) }

        // Pass 1 — alarm perangkat & collector menyemai akarnya masing-masing.
        impacts.filter { it.entityType != "ONU" }.forEach { imp ->
            bucket(Root(imp.entityType, imp.entityId, imp.label)).members += imp
        }

        // Pass 2 — kelompokkan dulu ONU menurut ODC hulunya, baru putuskan akarnya
        // supaya "≥2 di bawah ODC yang sama" bisa dinilai.
        val byOdc = onuImpacts.groupBy { imp ->
            val odpId = placementByOnu[imp.entityId]?.odpId ?: return@groupBy null
            upstreamByOdp.getOrPut(odpId) { networkApi.upstreamOf(odpId) }.odc?.id
        }
        byOdc.forEach { (odcId, group) ->
            group.forEach { imp ->
                val placement = placementByOnu[imp.entityId]
                val upstream = placement?.odpId?.let { upstreamByOdp[it] }
                val olt = upstream?.olt
                val odc = upstream?.odc
                val root = when {
                    olt != null && olt.id in alarmingDeviceIds -> Root("OLT", olt.id, olt.code)
                    odc != null && odc.id in alarmingDeviceIds -> Root("ODC", odc.id, odc.code)
                    odcId != null && group.size >= 2 && odc != null -> Root("ODC", odc.id, odc.code)
                    else -> Root("ONU", imp.entityId, imp.label)
                }
                bucket(root).apply {
                    members += imp
                    placement?.customerId?.let { customers += it }
                }
            }
        }

        return buckets.values.map { b ->
            val severity = severityName(b.members.maxOf { severityRank(it.severity) })
            IncidentView(
                key = "${b.root.type}:${b.root.id}",
                rootType = b.root.type,
                rootId = b.root.id,
                rootLabel = b.root.label,
                severity = severity,
                title = titleFor(b),
                alarmCount = b.members.size,
                affectedCustomerCount = b.customers.size,
                members = b.members.map {
                    IncidentAlarm(it.entityType, it.entityId, it.kind, it.severity, it.label)
                },
            )
        }.sortedWith(
            compareByDescending<IncidentView> { severityRank(it.severity) }.thenByDescending { it.alarmCount },
        )
    }

    private fun titleFor(b: Bucket): String {
        val single = b.members.singleOrNull()
        if (single != null && single.entityId == b.root.id) {
            return "${b.root.label} — ${humanKind(single.kind)}"
        }
        val n = b.customers.size.takeIf { it > 0 } ?: b.members.size
        val unit = if (b.customers.isNotEmpty()) "pelanggan" else "alarm"
        return "$n $unit terdampak di bawah ${b.root.label}"
    }

    private fun humanKind(kind: String): String = when (kind) {
        "ONU_LOS" -> "sinyal hilang (LOS)"
        "ONU_OFFLINE" -> "ONU offline"
        "ONU_LOW_RX" -> "redaman lemah"
        "OLT_UNREACHABLE" -> "OLT tidak terjangkau"
        "COLLECTOR_SILENT" -> "collector berhenti melapor"
        else -> kind
    }

    private fun severityRank(severity: String): Int = when (severity) {
        "CRITICAL" -> 2
        "WARNING" -> 1
        else -> 0
    }

    private fun severityName(rank: Int): String = when (rank) {
        2 -> "CRITICAL"
        1 -> "WARNING"
        else -> "INFO"
    }
}
