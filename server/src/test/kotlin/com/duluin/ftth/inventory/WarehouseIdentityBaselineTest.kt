package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.domain.model.Onu
import com.duluin.ftth.customer.domain.model.OnuStatus
import com.duluin.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.application.service.InventoryRegistryService
import com.duluin.ftth.inventory.domain.model.CustodyClaim
import com.duluin.ftth.inventory.domain.model.InventoryLocation
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.LocationKind
import com.duluin.ftth.inventory.domain.model.OwnerKind
import com.duluin.ftth.inventory.domain.model.SerializedAsset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class WarehouseIdentityBaselineTest {
    private val tenant = UUID.randomUUID()
    private val customer = UUID.randomUUID()
    private val location = InventoryLocation(UUID.randomUUID(), tenant, "WH-01", LocationKind.WAREHOUSE)

    @Test
    fun `legacy registration trims serial but preserves case and MAC separator`() {
        val assets = Mockito.mock(SerializedAssetRepository::class.java) { invocation ->
            if (invocation.method.name == "save") invocation.arguments[0]
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val locations = Mockito.mock(InventoryLocationRepository::class.java)
        Mockito.`when`(locations.findById(location.id)).thenReturn(location)
        val result = TenantContext.runAs(tenant) {
            InventoryRegistryService(assets, locations).register(
                tenant, UUID.randomUUID(), "  ont-i001  ", "aa-bb-cc-dd-ee-ff", location.id, customer,
            )
        }
        assertThat(result.serialNumber).isEqualTo("ont-i001")
        assertThat(result.macAddress).isEqualTo("AA-BB-CC-DD-EE-FF")
        Mockito.verify(assets).existsHistoricalSerial(tenant, "ont-i001")
        Mockito.verify(assets).existsHistoricalMac(tenant, "AA-BB-CC-DD-EE-FF")
    }

    @Test
    fun `legacy inventory domain retains original identity strings`() {
        val asset = asset("  ont-i001  ", "aa:bb:cc:dd:ee:ff")
        assertThat(asset.serialNumber).isEqualTo("  ont-i001  ")
        assertThat(asset.macAddress).isEqualTo("aa:bb:cc:dd:ee:ff")
    }

    @Test
    fun `legacy MAC accepts uniform separators but not compact or mixed notation`() {
        assertThat(asset("ONT-1", "aa-bb-cc-dd-ee-ff").macAddress).isEqualTo("aa-bb-cc-dd-ee-ff")
        listOf("aabbccddeeff", "aa-bb:cc-dd:ee-ff").forEach { mac ->
            assertThatThrownBy { asset("ONT-1", mac) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `customer creation normalizes valid serial and retains existing format restrictions`() {
        assertThat(Onu.create(tenant, customer, "  ont-i001  ", null).serialNumber).isEqualTo("ONT-I001")
        listOf("abc", "a".repeat(61), "AA:BB:CC:DD:EE:FF", "", "  ").forEach { serial ->
            assertThatThrownBy { Onu.create(tenant, customer, serial, null) }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `customer rehydration does not normalize historical display`() {
        val onu = Onu.rehydrate(
            UUID.randomUUID(), tenant, customer, "  ont-i001  ", null, null, null, null, OnuStatus.PENDING, null,
        )
        assertThat(onu.serialNumber).isEqualTo("  ont-i001  ")
    }

    private fun asset(serial: String, mac: String?) = SerializedAsset(
        UUID.randomUUID(), tenant, UUID.randomUUID(), serial, mac, InventoryStatus.AVAILABLE, location.id,
        CustodyClaim(customer, OwnerKind.WAREHOUSE, location.id),
    )
}
