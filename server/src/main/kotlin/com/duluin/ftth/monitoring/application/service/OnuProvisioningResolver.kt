package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.monitoring.application.port.inbound.ProvisioningSuggestion
import com.duluin.ftth.monitoring.application.port.inbound.SuggestionConfidence
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnu
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OdpRef
import com.duluin.ftth.workorder.WorkOrderRef
import com.duluin.ftth.workorder.WorkorderApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menebak {pelanggan, ODP, port} untuk sebuah ONU liar, agar operator cukup
 * mengonfirmasi alih-alih mengetik ulang semuanya.
 *
 * Serial yang dilaporkan OLT tapi belum terdaftar TIDAK memberi petunjuk pelanggan
 * langsung: begitu terdaftar, ia diterima sebagai metrik biasa dan tak lagi masuk
 * kotak masuk. Maka petunjuknya datang dari dua arah — topologi (PON port pelapor
 * mempersempit ODP kandidat) dan niat pasang (WO PSB terbuka menunjuk pelanggan
 * yang memang sedang dijadwalkan dipasang). Bila ada order pasang terbuka, itulah
 * sinyal terkuat; bila tidak, dipilih pelanggan menunggu-instalasi terdekat ke ODP.
 */
@Service
@Transactional(readOnly = true)
class OnuProvisioningResolver(
    private val customerApi: CustomerApi,
    private val networkApi: NetworkApi,
    private val workorderApi: WorkorderApi,
) {
    /**
     * Meresolusi saran untuk banyak baris sekaligus. Kotak masuk provisioning
     * berisi sedikit baris (ONU liar yang belum dituntaskan), jadi resolusi
     * per-baris memadai; tak ada jalur panas yang perlu dibatch lebih jauh.
     * Daftar WO PSB terbuka dibaca sekali untuk seluruh batch.
     */
    fun resolveAll(discovered: List<DiscoveredOnu>): Map<UUID, ProvisioningSuggestion> {
        if (discovered.isEmpty()) return emptyMap()
        val openPsb = workorderApi.openPsbByCustomer()
        return discovered.associate { it.id to resolve(it, openPsb) }
    }

    fun resolve(discovered: DiscoveredOnu): ProvisioningSuggestion =
        resolve(discovered, workorderApi.openPsbByCustomer())

    private fun resolve(discovered: DiscoveredOnu, openPsb: Map<UUID, WorkOrderRef>): ProvisioningSuggestion {
        val oltId = discovered.oltId
            ?: return none("OLT ${discovered.oltCode} belum dikenal inventory — petakan dulu OLT-nya.")

        // Topologi: PON port pelapor → ODP kandidat, disaring ke yang masih punya port kosong.
        val candidates = networkApi.candidateOdpsUnderPonPort(oltId, discovered.ponPortLabel)
            .mapNotNull { odp -> firstFreePort(odp)?.let { OdpSlot(odp, it) } }
        if (candidates.isEmpty()) {
            return none("PON port ${discovered.ponPortLabel ?: "?"} belum terpetakan ke ODP dengan port kosong.")
        }

        // Pelanggan yang menunggu instalasi di area ODP kandidat.
        val areaIds = candidates.mapNotNullTo(HashSet<UUID>()) { it.odp.areaId }
        val awaiting = customerApi.findAwaitingInstallation(if (areaIds.isEmpty()) null else areaIds)

        // Yang punya WO PSB terbuka = sinyal terkuat; bila ada, hanya mereka yang dipertimbangkan.
        val psbBacked = awaiting.filter { it.id in openPsb }
        val pool = psbBacked.ifEmpty { awaiting }
        val match = pool
            .flatMap { c -> candidates.map { slot -> Match(c, slot, c.location.distanceTo(slot.odp.location)) } }
            .minByOrNull { it.distanceMeters }

        return when {
            match != null -> {
                val wo = openPsb[match.customer.id]
                // Pelanggan pasti bila hanya satu order terbuka, atau memang hanya satu yang menunggu.
                val confidentCustomer = psbBacked.size == 1 || awaiting.size == 1
                val confidentOdp = candidates.size == 1
                val high = confidentCustomer && confidentOdp
                ProvisioningSuggestion(
                    confidence = if (high) SuggestionConfidence.HIGH else SuggestionConfidence.MEDIUM,
                    customerId = match.customer.id,
                    customerName = match.customer.name,
                    odpId = match.slot.odp.id,
                    odpCode = match.slot.odp.code,
                    portNumber = match.slot.port,
                    reason = reasonFor(match, wo, awaiting.size, psbBacked.size, candidates.size, high),
                )
            }

            candidates.size == 1 -> {
                val slot = candidates.single()
                ProvisioningSuggestion(
                    confidence = SuggestionConfidence.LOW,
                    customerId = null,
                    customerName = null,
                    odpId = slot.odp.id,
                    odpCode = slot.odp.code,
                    portNumber = slot.port,
                    reason = "${slot.odp.code} port ${slot.port} satu-satunya kandidat di PON ini; pilih pelanggannya.",
                )
            }

            else -> none(
                "${candidates.size} ODP kandidat di PON ini, tapi tak ada pelanggan menunggu instalasi — pilih manual.",
            )
        }
    }

    private fun reasonFor(
        match: Match,
        wo: WorkOrderRef?,
        awaitingCount: Int,
        psbCount: Int,
        candidateCount: Int,
        high: Boolean,
    ): String {
        val target = "${match.slot.odp.code} port ${match.slot.port}"
        return when {
            wo != null && high ->
                "WO PSB ${wo.code} terbuka untuk ${match.customer.name} — pasang di $target."
            wo != null ->
                "${match.customer.name} punya WO PSB ${wo.code} terbuka " +
                    "($psbCount pelanggan ber-order, $candidateCount ODP kandidat) — mohon periksa."
            high ->
                "Cocok tunggal: ${match.customer.name} menunggu instalasi, $target kosong."
            else ->
                "${match.customer.name} pelanggan menunggu-instalasi terdekat ke ${match.slot.odp.code} " +
                    "(±${match.distanceMeters.toInt()} m); $awaitingCount menunggu, $candidateCount ODP — mohon periksa."
        }
    }

    /** Port kosong pertama pada sebuah ODP aktif; `null` bila penuh atau belum aktif. */
    private fun firstFreePort(odp: OdpRef): Int? {
        if (!odp.active) return null
        val occupied = customerApi.occupiedPortsOn(odp.id)
        return (1..odp.capacity).firstOrNull { it !in occupied }
    }

    private fun none(reason: String) = ProvisioningSuggestion(
        confidence = SuggestionConfidence.NONE,
        customerId = null,
        customerName = null,
        odpId = null,
        odpCode = null,
        portNumber = null,
        reason = reason,
    )

    private data class OdpSlot(val odp: OdpRef, val port: Int)
    private data class Match(val customer: CustomerRef, val slot: OdpSlot, val distanceMeters: Double)
}
