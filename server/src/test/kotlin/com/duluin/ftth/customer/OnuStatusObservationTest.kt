package com.duluin.ftth.customer

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.customer.domain.model.Onu
import com.duluin.ftth.customer.domain.model.OnuStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Status ONU hanya boleh lahir dari pengamatan nyata (polling SNMP OLT) atau
 * tindakan operator — memasangnya ke port ODP TIDAK boleh mengarang status.
 *
 * Dulu `attachTo` menjadikan PENDING → OFFLINE, sehingga ONU yang serialnya tak
 * pernah muncul di walk OLT selamanya tampil "Offline" yang meyakinkan padahal
 * kabarnya tak diketahui — operator melihat panel ODP bilang mati sementara sesi
 * PPPoE pelanggan yang sama jelas hidup. PENDING kini berarti "belum terpantau".
 */
class OnuStatusObservationTest {

    @Test
    fun `memasang ONU tak mengarang status offline`() {
        val onu = newOnu()
        assertThat(onu.status).isEqualTo(OnuStatus.PENDING)

        onu.attachTo(odpId = UuidV7.generate(), portNumber = 3, rxPowerDbm = -21.5)

        // Terpasang (punya port & tanggal pasang) tapi kabarnya belum diketahui.
        assertThat(onu.attached).isTrue()
        assertThat(onu.installedAt).isNotNull()
        assertThat(onu.status).isEqualTo(OnuStatus.PENDING)
    }

    @Test
    fun `pengamatan pertama yang menentukan status`() {
        val onu = newOnu()
        onu.attachTo(odpId = UuidV7.generate(), portNumber = 3, rxPowerDbm = null)

        onu.changeStatus(OnuStatus.ONLINE)

        assertThat(onu.status).isEqualTo(OnuStatus.ONLINE)
    }

    private fun newOnu() = Onu.create(
        tenantId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        serialNumber = "ZTEG12345678",
        model = "F670L",
    )
}
