package com.duluin.ftth.customer.application.service

import com.duluin.ftth.customer.application.port.outbound.OnuRepository
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
) : OdpUsageProbe {

    override fun countAttachedTo(odpId: UUID): Long = onuRepository.findByOdpId(odpId).size.toLong()

    /** Satu query agregat, bukan satu query per ODP — lihat alasannya di kontraknya. */
    override fun countAttachedTo(odpIds: Set<UUID>): Map<UUID, Long> =
        if (odpIds.isEmpty()) emptyMap() else onuRepository.countByOdpIds(odpIds)

    override fun describeUsage(): String = "ONU pelanggan"
}
