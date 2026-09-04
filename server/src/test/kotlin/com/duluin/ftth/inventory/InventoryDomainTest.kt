package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
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
import java.util.UUID

class InventoryDomainTest {
    private val tenant = UUID.randomUUID()
    private val warehouse = InventoryLocation(UUID.randomUUID(), tenant, "WH-01", LocationKind.WAREHOUSE)
    private val bin = InventoryLocation(UUID.randomUUID(), tenant, "BIN-A", LocationKind.BIN)

    @Test
    fun `serial and MAC are unique and historical values cannot be reused`() {
        val registry = InventoryRegistryService(FakeAssets(), FakeLocations(warehouse, bin))
        TenantContext.runAs(tenant) {
            registry.register(tenant, UUID.randomUUID(), "ONT-0001", "AA:BB:CC:DD:EE:01", warehouse.id, UUID.randomUUID())
            assertThatThrownBy { registry.register(tenant, UUID.randomUUID(), "ONT-0001", "AA:BB:CC:DD:EE:02", warehouse.id, UUID.randomUUID()) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { registry.register(tenant, UUID.randomUUID(), "ONT-0002", "AA:BB:CC:DD:EE:01", warehouse.id, UUID.randomUUID()) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `transition rejects unofficial destination and invalid state`() {
        val asset = asset(warehouse)
        assertThatThrownBy { asset.transition(InventoryStatus.ISSUED, InventoryLocation(UUID.randomUUID(), tenant, "NOPE", LocationKind.BIN), CustodyClaim(UUID.randomUUID(), OwnerKind.WAREHOUSE, warehouse.id)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { asset.transition(InventoryStatus.CONSUMED, warehouse, CustodyClaim(UUID.randomUUID(), OwnerKind.WAREHOUSE, warehouse.id)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ONU linkage is single owner and only issued assets can link`() {
        val issued = asset(warehouse).transition(InventoryStatus.ISSUED, bin, CustodyClaim(UUID.randomUUID(), OwnerKind.TECHNICIAN, bin.id))
        val onu = UUID.randomUUID()
        assertThat(issued.linkInstalledOnu(onu).linkInstalledOnu(onu).installedOnuId).isEqualTo(onu)
        assertThatThrownBy { issued.linkInstalledOnu(UUID.randomUUID()).linkInstalledOnu(UUID.randomUUID()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { asset(warehouse).linkInstalledOnu(onu) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `tenant context prevents spoofing`() {
        val registry = InventoryRegistryService(FakeAssets(), FakeLocations(warehouse, bin))
        val other = UUID.randomUUID()
        assertThatThrownBy {
            TenantContext.runAs(other) { registry.register(tenant, UUID.randomUUID(), "ONT-0009", null, warehouse.id, UUID.randomUUID()) }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun asset(location: InventoryLocation) = SerializedAsset(UUID.randomUUID(), tenant, UUID.randomUUID(), "ONT-X", null, InventoryStatus.AVAILABLE, location.id, CustodyClaim(UUID.randomUUID(), OwnerKind.WAREHOUSE, location.id))

    private class FakeLocations(vararg values: InventoryLocation) : InventoryLocationRepository {
        private val values = values.associateBy { it.id }.toMutableMap()
        override fun findById(id: UUID) = values[id]
        override fun save(location: InventoryLocation) = location.also { values[it.id] = it }
    }

    private class FakeAssets : SerializedAssetRepository {
        private val values = mutableListOf<SerializedAsset>()
        override fun findById(id: UUID) = values.firstOrNull { it.id == id }
        override fun findBySerial(tenantId: UUID, serialNumber: String) = values.firstOrNull { it.tenantId == tenantId && it.serialNumber == serialNumber }
        override fun findByMac(tenantId: UUID, macAddress: String) = values.firstOrNull { it.tenantId == tenantId && it.macAddress == macAddress }
        override fun save(asset: SerializedAsset) = asset.also { values.removeIf { old -> old.id == asset.id }; values += asset }
        override fun existsHistoricalSerial(tenantId: UUID, serialNumber: String) = findBySerial(tenantId, serialNumber) != null
        override fun existsHistoricalMac(tenantId: UUID, macAddress: String) = findByMac(tenantId, macAddress) != null
        override fun findByOperation(tenantId: UUID, operationKey: String) = null
    }
}
