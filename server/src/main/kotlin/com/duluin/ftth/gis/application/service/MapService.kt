package com.duluin.ftth.gis.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.gis.application.port.inbound.CustomerTrace
import com.duluin.ftth.gis.application.port.inbound.MapQuery
import com.duluin.ftth.gis.application.port.inbound.OdpInspection
import com.duluin.ftth.gis.application.port.inbound.TraceHop
import com.duluin.ftth.gis.application.port.inbound.UpstreamView
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.UpstreamPath
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.roundToInt

@Service
@Transactional(readOnly = true)
class MapService(
    private val networkApi: NetworkApi,
    private val customerApi: CustomerApi,
    private val currentUser: CurrentUserProvider,
) : MapQuery {

    /**
     * Menggabungkan tile jaringan dan tile pelanggan. Penyambungan byte MVT sah
     * secara protobuf: `layers` adalah repeated field, jadi hasilnya satu tile
     * berisi seluruh layer dari kedua module.
     */
    override fun renderTile(z: Int, x: Int, y: Int): ByteArray {
        val areaIds = currentUser.current().areaScope()
        return networkApi.renderMapTile(z, x, y, areaIds) + customerApi.renderMapTile(z, x, y, areaIds)
    }

    override fun inspectOdp(odpId: UUID): OdpInspection {
        val odp = networkApi.requireOdp(odpId)
        val upstream = networkApi.upstreamOf(odpId)
        val occupants = customerApi.findOccupantsOfOdp(odpId)
        val occupiedPorts = occupants.mapTo(HashSet()) { it.portNumber }

        return OdpInspection(
            odpId = odp.id,
            code = odp.code,
            name = odp.name,
            location = odp.location,
            capacity = odp.capacity,
            usedPorts = occupiedPorts.size,
            availablePortNumbers = (1..odp.capacity).filterNot { it in occupiedPorts },
            utilizationPercent = percentage(occupiedPorts.size, odp.capacity),
            upstream = upstream.toView(),
            occupants = occupants,
        )
    }

    /**
     * Menelusuri jalur seorang pelanggan ke hulu.
     *
     * Pelanggan yang ONU-nya belum terpasang tetap dikembalikan — dengan hulu
     * kosong. Justru inilah yang ingin dilihat operator: pelanggan yang terdaftar
     * tapi belum tersambung ke jaringan.
     */
    override fun traceCustomer(customerId: UUID): CustomerTrace {
        val customer = customerApi.findCustomer(customerId)
            ?: throw NotFoundException("Pelanggan $customerId tidak ditemukan")

        val placement = customerApi.findPlacementOf(customerId)
        val upstream = placement?.let { networkApi.upstreamOf(it.odpId) }

        return CustomerTrace(
            customerId = customer.id,
            customerCode = customer.code,
            customerName = customer.name,
            location = customer.location,
            onuSerialNumber = placement?.onuSerialNumber,
            onuStatus = placement?.onuStatus,
            installRxPowerDbm = placement?.installRxPowerDbm,
            opticalHealth = placement?.opticalHealth,
            odpPortNumber = placement?.portNumber,
            upstream = upstream?.toView(),
            estimatedLossDb = upstream?.let { estimateLoss(it, customer.location) },
            hops = buildHops(customer.location, upstream),
        )
    }

    private fun UpstreamPath.toView() = UpstreamView(
        odcCode = odc?.code,
        odcName = odc?.name,
        ponPortLabel = ponPort?.code,
        oltCode = olt?.code,
        oltName = olt?.name,
        siteCode = site?.code,
        siteName = site?.name,
        complete = complete,
        splitterLossDb = splitterLossDb,
    )

    /**
     * Rugi splitter ditambah redaman serat menurut jarak garis lurus ODP→rumah.
     * Koefisien 0,35 dB/km adalah angka lazim untuk 1310 nm; jarak sebenarnya
     * selalu lebih panjang dari garis lurus, jadi ini batas bawah yang optimistis.
     */
    private fun estimateLoss(upstream: UpstreamPath, customerLocation: Coordinate): Double {
        val distanceKm = upstream.odp.location.distanceTo(customerLocation) / 1_000
        return upstream.splitterLossDb + distanceKm * FIBER_LOSS_DB_PER_KM
    }

    private fun buildHops(customerLocation: Coordinate, upstream: UpstreamPath?): List<TraceHop> = buildList {
        add(TraceHop("CUSTOMER", "", "Rumah pelanggan", customerLocation))
        upstream ?: return@buildList
        add(TraceHop("ODP", upstream.odp.code, upstream.odp.name, upstream.odp.location))
        upstream.odc?.let { add(TraceHop("ODC", it.code, it.name, null)) }
        upstream.ponPort?.let { add(TraceHop("PON_PORT", it.code, it.name, null)) }
        upstream.olt?.let { add(TraceHop("OLT", it.code, it.name, null)) }
        upstream.site?.let { add(TraceHop("SITE", it.code, it.name, null)) }
    }

    private fun percentage(used: Int, total: Int): Int =
        if (total == 0) 0 else (used.toDouble() / total * 100).roundToInt()

    private companion object {
        const val FIBER_LOSS_DB_PER_KM = 0.35
    }
}
