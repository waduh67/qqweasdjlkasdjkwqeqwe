package com.duluin.ftth.cpe

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository
import com.duluin.ftth.cpe.application.service.CpeApiService
import com.duluin.ftth.cpe.domain.model.CpeDevice
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Menguji [CpeApiService] memakai fake repo murni: online dihitung dari inform terakhir
 * terhadap ambang basi, dan atribut proyeksi dipetakan apa adanya ke ref lintas-modul.
 */
class CpeApiServiceTest {

    private val customerId = UuidV7.generate()
    private val staleAfter = Duration.ofMinutes(15)

    @Test
    fun `memetakan status dan menghitung online per device`() {
        val now = Instant.now()
        val online = device(serial = "SN-ONLINE", model = "HG8145", lastInformAt = now.minusSeconds(300))   // 5 mnt < 15
        val offline = device(serial = "SN-OFFLINE", model = "F670L", lastInformAt = now.minusSeconds(1800))  // 30 mnt > 15
        val never = device(serial = "SN-NEVER", model = null, lastInformAt = null)
        val service = CpeApiService(FakeCpeDeviceRepository(listOf(online, offline, never)), staleAfter)

        val refs = service.findDevicesForCustomer(customerId)

        assertThat(refs).hasSize(3)
        val byserial = refs.associateBy { it.serialNumber }
        assertThat(byserial.getValue("SN-ONLINE").online).isTrue()
        assertThat(byserial.getValue("SN-ONLINE").model).isEqualTo("HG8145")
        assertThat(byserial.getValue("SN-OFFLINE").online).isFalse()
        assertThat(byserial.getValue("SN-NEVER").online).isFalse()
        assertThat(byserial.getValue("SN-NEVER").lastInformAt).isNull()
    }

    @Test
    fun `pelanggan tanpa cpe mengembalikan daftar kosong`() {
        val service = CpeApiService(FakeCpeDeviceRepository(emptyList()), staleAfter)

        assertThat(service.findDevicesForCustomer(customerId)).isEmpty()
    }

    private fun device(serial: String, model: String?, lastInformAt: Instant?): CpeDevice = CpeDevice.rehydrate(
        id = UuidV7.generate(),
        genieacsId = "acs-$serial",
        serialNumber = serial,
        oui = null,
        productClass = null,
        manufacturer = "Huawei",
        model = model,
        softwareVersion = "V1.0",
        ipAddress = "10.0.0.1",
        lastInformAt = lastInformAt,
        customerId = customerId,
        onuId = null,
    )

    private class FakeCpeDeviceRepository(private val devices: List<CpeDevice>) : CpeDeviceRepository {
        override fun findByCustomerId(customerId: UUID): List<CpeDevice> = devices

        override fun save(device: CpeDevice) = throw UnsupportedOperationException()
        override fun findById(id: UUID) = throw UnsupportedOperationException()
        override fun findByGenieacsId(genieacsId: String) = throw UnsupportedOperationException()
        override fun findAllForCurrentTenant() = throw UnsupportedOperationException()
        override fun deleteByIds(ids: Collection<UUID>) = throw UnsupportedOperationException()
    }
}
