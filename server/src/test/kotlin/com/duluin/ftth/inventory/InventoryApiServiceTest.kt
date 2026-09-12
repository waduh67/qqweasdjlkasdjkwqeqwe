package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.DurableInventoryFulfillmentService
import com.duluin.ftth.inventory.application.service.InventoryApiService
import com.duluin.ftth.inventory.domain.model.CustodyClaim
import com.duluin.ftth.inventory.domain.model.InventoryLocation
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.LocationKind
import com.duluin.ftth.inventory.domain.model.OwnerKind
import com.duluin.ftth.inventory.domain.model.SerializedAsset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID

class InventoryApiServiceTest {
    private val tenant = UUID.randomUUID()
    private val bin = InventoryLocation(UUID.randomUUID(), tenant, "BIN-A", LocationKind.BIN)

    /**
     * Skenario gagal yang dijaga tes ini: teknisi menekan "pasang ONU" di lapangan, jaringan
     * putus sebelum balasannya sampai, lalu aplikasi mengirim ulang permintaan yang SAMA.
     * Kalau `operationKey` tidak ikut tersimpan di baris aset, percobaan kedua tidak dikenali
     * sebagai kiriman ulang dan efeknya dijalankan dua kali.
     */
    @Test
    fun `linkInstalledOnu replays by operation key without writing twice`() {
        val assets = FakeSerializedAssets()
        val asset = SerializedAsset(
            UUID.randomUUID(), tenant, UUID.randomUUID(), "ONT-IDEMPOTENT", null,
            InventoryStatus.AVAILABLE, bin.id, CustodyClaim(UUID.randomUUID(), OwnerKind.WAREHOUSE, bin.id),
        ).transition(InventoryStatus.ISSUED, bin, CustodyClaim(UUID.randomUUID(), OwnerKind.TECHNICIAN, bin.id))
        assets.save(asset)
        val service = InventoryApiService(assets, mock(DurableInventoryFulfillmentService::class.java))
        val onu = UUID.randomUUID()

        val (first, second, writes) = TenantContext.runAs(tenant) {
            val first = service.linkInstalledOnu(asset.id, onu, "link-onu-1")
            val writesAfterFirst = assets.saveCount
            // ONU berbeda dengan kunci operasi yang sama: kalau ini diterima, kunci idempotency
            // tidak dipakai sama sekali dan aset bisa berpindah pemilik diam-diam.
            val second = service.linkInstalledOnu(asset.id, UUID.randomUUID(), "link-onu-1")
            Triple(first, second, assets.saveCount - writesAfterFirst)
        }

        assertThat(first.installedOnuId).isEqualTo(onu)
        assertThat(second).isEqualTo(first)
        assertThat(writes).isZero()
    }
}
