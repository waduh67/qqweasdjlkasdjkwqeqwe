package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.network.OdpPortOccupant
import com.duluin.ftth.network.OdpUsageProbe
import com.duluin.ftth.network.application.port.inbound.OdpPortBoardView
import com.duluin.ftth.network.application.port.inbound.OdpPortRowView
import com.duluin.ftth.network.application.port.inbound.ViewOdpPortsUseCase
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.SplitterRepository
import com.duluin.ftth.network.domain.model.OdpPortIssue
import com.duluin.ftth.network.domain.model.Splitter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menyandingkan dua catatan tentang isi sebuah ODP: pemasangan ONU (module
 * customer) dan sambungan serat (module network).
 *
 * Keduanya dibuat orang yang berbeda pada waktu yang berbeda, jadi keduanya bisa
 * benar sendiri-sendiri sambil bertengkar satu sama lain — dan tak ada satu pun
 * layar di sistem ini yang selama ini mempertemukannya. Akibatnya baru terasa di
 * lapangan: port yang katanya kosong ternyata terisi, pelanggan yang mati saat
 * tetangganya diperbaiki, kaki yang dicabut satu per satu untuk mencari milik
 * siapa. Papan ini menaruh keduanya berdampingan supaya selisihnya kelihatan
 * dari kantor, sebelum ada yang berangkat.
 */
@Service
@Transactional(readOnly = true)
class OdpPortBoardService(
    private val odpRepository: OdpRepository,
    private val splitters: SplitterRepository,
    private val legTracer: SplitterLegTracer,
    /** Kosong bila belum ada module lain yang menempel pada ODP. */
    private val usageProbes: List<OdpUsageProbe>,
) : ViewOdpPortsUseCase {

    override fun ports(odpId: UUID): OdpPortBoardView {
        val odp = odpRepository.findById(odpId) ?: throw NotFoundException("ODP $odpId tidak ditemukan")
        val modules = splitters.findByOwnerId(odpId)
        val loads = legTracer.trace(odpId, modules)
        val occupants = usageProbes.flatMap { it.occupantsOf(odpId) }

        val legOfPort = mapLegsToPorts(modules)
        // Port sebenarnya menurut SERAT untuk tiap pelanggan — dipakai menempatkan
        // ONU yang catatan portnya kosong. Kalau seratnya sudah bercerita, tak ada
        // gunanya menampilkan barisnya menggantung di bawah tanpa petunjuk.
        val portByFiber = legOfPort.entries.mapNotNull { (port, leg) ->
            loads[LegKey(leg.module.id, leg.number)]?.customerId?.let { it to port }
        }.toMap()
        val byPort = occupants
            .mapNotNull { occupant -> (occupant.portNumber ?: portByFiber[occupant.customerId])?.to(occupant) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, sharing) -> sharing.first() }

        // Baris tak pernah kurang dari kapasitas faceplate, tapi ikut melar bila
        // ada kaki atau catatan yang menunjuk lubang di luarnya — keadaan janggal
        // yang justru paling perlu terlihat, bukan disembunyikan.
        val lastPort = maxOf(odp.capacity, byPort.keys.maxOrNull() ?: 0, legOfPort.keys.maxOrNull() ?: 0)
        val rows = (1..lastPort).map { port -> row(port, legOfPort[port], loads, byPort[port]) } +
            occupants.filter { it.portNumber == null && it.customerId !in portByFiber }.map { stray(it) }

        return OdpPortBoardView(
            odpId = odp.id,
            odpCode = odp.code,
            capacity = odp.capacity,
            splitterCodes = modules.map { it.code },
            ports = rows,
            occupiedCount = rows.count { it.customerId != null },
            issueCount = rows.count { it.issue != null },
        )
    }

    private fun row(
        port: Int,
        leg: LegSlot?,
        loads: Map<LegKey, LegLoad>,
        occupant: OdpPortOccupant?,
    ): OdpPortRowView {
        val load = leg?.let { loads[LegKey(it.module.id, it.number)] }
        val issue = when {
            load?.backward == true -> OdpPortIssue.LEG_BACKWARD
            // Portnya kita simpulkan dari serat, bukan dari catatannya sendiri.
            occupant?.portNumber == null && occupant != null -> OdpPortIssue.PORT_UNRECORDED
            occupant != null && load == null -> OdpPortIssue.PORT_WITHOUT_FIBER
            occupant == null && load?.customerId != null -> OdpPortIssue.FIBER_WITHOUT_PORT
            occupant != null && load?.customerId != null && load.customerId != occupant.customerId ->
                OdpPortIssue.PORT_MISMATCH
            else -> null
        }
        return OdpPortRowView(
            portNumber = port,
            legLabel = leg?.let { "${it.module.code} kaki ${it.number}" },
            legConnected = load != null,
            servedBy = load?.describe(),
            customerId = occupant?.customerId,
            customerName = occupant?.customerName,
            onuSerialNumber = occupant?.onuSerialNumber,
            onuStatus = occupant?.onuStatus,
            opticalHealth = occupant?.opticalHealth,
            rxPowerDbm = occupant?.rxPowerDbm,
            issue = issue,
            issueLabel = issue?.label,
            issueDetail = issue?.detail,
        )
    }

    /** ONU yang tercatat di kotak ini tanpa port, dan seratnya pun belum bercerita. */
    private fun stray(occupant: OdpPortOccupant) = OdpPortRowView(
        portNumber = null,
        legLabel = null,
        legConnected = false,
        servedBy = null,
        customerId = occupant.customerId,
        customerName = occupant.customerName,
        onuSerialNumber = occupant.onuSerialNumber,
        onuStatus = occupant.onuStatus,
        opticalHealth = occupant.opticalHealth,
        rxPowerDbm = occupant.rxPowerDbm,
        issue = OdpPortIssue.PORT_UNRECORDED,
        issueLabel = OdpPortIssue.PORT_UNRECORDED.label,
        issueDetail = OdpPortIssue.PORT_UNRECORDED.detail,
    )

    /**
     * Kaki modul mana yang dipigtail ke lubang faceplate mana.
     *
     * Nomor berjalan menurut urutan modul: modul pertama mengisi lubang 1 sampai
     * sebanyak kakinya, modul berikutnya melanjut. Untuk ODP satu modul — bentuk
     * yang hampir selalu dipakai — ini persis "kaki N = port N", dan memang
     * begitulah pigtail terpasang dari pabriknya. ODP dua modul jarang ada, dan
     * bila urutannya di lapangan ternyata lain, yang kelihatan adalah nama
     * pelanggan yang tak cocok — bukan diam-diam salah.
     */
    private fun mapLegsToPorts(modules: List<Splitter>): Map<Int, LegSlot> {
        var port = 0
        return modules.flatMap { module ->
            (1..module.legCount).map { leg -> ++port to LegSlot(module, leg) }
        }.toMap()
    }

    private data class LegSlot(val module: Splitter, val number: Int)
}
