package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.monitoring.application.port.inbound.ProvisioningSuggestion
import com.duluin.ftth.monitoring.application.port.inbound.SuggestionConfidence
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnu
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OdpRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menebak {pelanggan, ODP, port} untuk sebuah ONU liar, agar operator cukup
 * mengonfirmasi alih-alih mengetik ulang semuanya.
 *
 * Serial yang dilaporkan OLT tapi belum terdaftar TIDAK memberi petunjuk pelanggan
 * langsung: begitu terdaftar, ia diterima sebagai metrik biasa dan tak lagi masuk
 * kotak masuk. Maka sinyal terkuatnya justru topologi — PON port pelapor
 * mempersempit ODP kandidat — lalu di antara pelanggan yang menunggu instalasi
 * dipilih yang paling dekat ke ODP itu.
 */
@Service
@Transactional(readOnly = true)
class OnuProvisioningResolver(
    private val customerApi: CustomerApi,
    private val networkApi: NetworkApi,
) {
    /**
     * Meresolusi saran untuk banyak baris sekaligus. Kotak masuk provisioning
     * berisi sedikit baris (ONU liar yang belum dituntaskan), jadi resolusi
     * per-baris memadai; tak ada jalur panas yang perlu dibatch lebih jauh.
     */
    fun resolveAll(discovered: List<DiscoveredOnu>): Map<UUID, ProvisioningSuggestion> =
        discovered.associate { it.id to resolve(it) }

    fun resolve(discovered: DiscoveredOnu): ProvisioningSuggestion {
        val oltId = discovered.oltId
            ?: return none("OLT ${discovered.oltCode} belum dikenal inventory — petakan dulu OLT-nya.")

        // Topologi: PON port pelapor → ODP kandidat, disaring ke yang masih punya port kosong.
        val candidates = networkApi.candidateOdpsUnderPonPort(oltId, discovered.ponPortLabel)
            .mapNotNull { odp -> firstFreePort(odp)?.let { OdpSlot(odp, it) } }
        if (candidates.isEmpty()) {
            return none("PON port ${discovered.ponPortLabel ?: "?"} belum terpetakan ke ODP dengan port kosong.")
        }

        // Pelanggan yang menunggu instalasi di area ODP kandidat; pasangkan yang paling dekat.
        val areaIds = candidates.mapNotNullTo(HashSet<UUID>()) { it.odp.areaId }
        val awaiting = customerApi.findAwaitingInstallation(if (areaIds.isEmpty()) null else areaIds)
        val match = awaiting
            .flatMap { c -> candidates.map { slot -> Match(c, slot, c.location.distanceTo(slot.odp.location)) } }
            .minByOrNull { it.distanceMeters }

        return when {
            match != null -> {
                val unambiguous = awaiting.size == 1 && candidates.size == 1
                ProvisioningSuggestion(
                    confidence = if (unambiguous) SuggestionConfidence.HIGH else SuggestionConfidence.MEDIUM,
                    customerId = match.customer.id,
                    customerName = match.customer.name,
                    odpId = match.slot.odp.id,
                    odpCode = match.slot.odp.code,
                    portNumber = match.slot.port,
                    reason = if (unambiguous) {
                        "Cocok tunggal: ${match.customer.name} menunggu instalasi, " +
                            "${match.slot.odp.code} port ${match.slot.port} kosong."
                    } else {
                        "${match.customer.name} pelanggan menunggu-instalasi terdekat ke ${match.slot.odp.code} " +
                            "(±${match.distanceMeters.toInt()} m); ${candidates.size} ODP kandidat — mohon periksa."
                    },
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
