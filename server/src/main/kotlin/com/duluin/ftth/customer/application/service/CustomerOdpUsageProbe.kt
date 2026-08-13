package com.duluin.ftth.customer.application.service

import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.network.OdpPortOccupant
import com.duluin.ftth.network.OdpUsageProbe
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Memberi tahu module network bahwa sebuah ODP masih dipakai pelanggan, sehingga
 * penghapusannya ditolak alih-alih diam-diam memutus mereka dari peta.
 */
@Component
class CustomerOdpUsageProbe(
    private val onuRepository: OnuRepository,
    private val customerRepository: CustomerRepository,
) : OdpUsageProbe {

    override fun countAttachedTo(odpId: UUID): Long = onuRepository.findByOdpId(odpId).size.toLong()

    /** Satu query agregat, bukan satu query per ODP — lihat alasannya di kontraknya. */
    override fun countAttachedTo(odpIds: Set<UUID>): Map<UUID, Long> =
        if (odpIds.isEmpty()) emptyMap() else onuRepository.countByOdpIds(odpIds)

    /**
     * ONU yang belum ditempelkan ke port mana pun (baru didaftarkan, menunggu
     * pemasangan) tak menempati apa-apa, jadi tak ikut menahan pengecilan kotak.
     */
    override fun occupiedPortsOn(odpId: UUID): Set<Int> =
        onuRepository.findByOdpId(odpId).mapNotNullTo(HashSet()) { it.odpPortNumber }

    /**
     * ONU yang belum berport IKUT dilaporkan di sini — berbeda dengan
     * [occupiedPortsOn] yang cuma menghitung penghuni.
     *
     * "Barangnya sudah di rumah orang tapi belum tercatat di port mana" adalah
     * justru salah satu keadaan yang ingin dilihat teknisi saat membuka kotaknya:
     * ia mencari kaki mana yang menyalurkan pelanggan ini, dan catatannya diam.
     */
    override fun occupantsOf(odpId: UUID): List<OdpPortOccupant> {
        val onus = onuRepository.findByOdpId(odpId)
        if (onus.isEmpty()) return emptyList()
        val names = customerRepository.findAllByIds(onus.mapTo(HashSet()) { it.customerId })
            .associate { it.id to it.name }
        return onus.map { onu ->
            OdpPortOccupant(
                portNumber = onu.odpPortNumber,
                customerId = onu.customerId,
                customerName = names[onu.customerId] ?: "Pelanggan tak dikenal",
                onuSerialNumber = onu.serialNumber,
                onuStatus = onu.status.name,
                opticalHealth = onu.opticalHealth().name,
                rxPowerDbm = onu.installRxPowerDbm,
            )
        }.sortedWith(compareBy(nullsLast()) { it.portNumber })
    }

    override fun describeUsage(): String = "ONU pelanggan"
}
