package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Menjaga ujung kabel tetap menempel ke simpulnya saat simpul dipindah.
 *
 * Kabel digambar sebagai polyline berdiri sendiri — titik-titiknya tak menyimpan
 * rujukan koordinat simpul — jadi memindah OLT/ODC/ODP/site/pelanggan tak otomatis
 * menggeser kabel. Layanan ini menutup celah itu: untuk tiap kabel yang menyentuh
 * simpul, HANYA titik ujung yang menyambung ke simpul itu di-snap ke koordinat
 * baru; tikungan di tengah dibiarkan apa adanya dan panjang dihitung ulang dari
 * geometri saat disimpan. Idempoten — kabel yang ujungnya sudah pas tak ditulis
 * ulang. Dipanggil node service network maupun (lewat NetworkApi) module customer
 * untuk kabel drop-nya.
 */
@Service
@Transactional
class CableAttachmentService(
    private val cableRepository: CableRepository,
) {
    fun resnapForMovedNode(ref: NetworkNodeRef, coord: Coordinate) {
        cableRepository.findByEndpoint(ref).forEach { cable ->
            if (cable.snapEndpointTo(ref, coord)) cableRepository.save(cable)
        }
    }
}
