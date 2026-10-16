package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.karuhun.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.karuhun.ftth.inventory.domain.model.CustodyClaim
import com.karuhun.ftth.inventory.domain.model.InventoryStatus
import com.karuhun.ftth.inventory.domain.model.OwnerKind
import com.karuhun.ftth.inventory.domain.model.SerializedAsset
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InventoryRegistryService(
    private val assets: SerializedAssetRepository,
    private val locations: InventoryLocationRepository,
) {
    @Transactional
    fun register(
        tenantId: UUID,
        skuId: UUID,
        serialNumber: String,
        macAddress: String?,
        locationId: UUID,
        custodyOwnerId: UUID,
    ): SerializedAsset {
        require(TenantContext.tenantId() == tenantId) { "tenant must come from authenticated context" }
        require(!assets.existsHistoricalSerial(tenantId, serialNumber.trim())) { "serial number already used" }
        require(macAddress == null || !assets.existsHistoricalMac(tenantId, macAddress.uppercase())) { "MAC address already used" }
        val location = locations.findById(locationId) ?: error("location does not exist")
        require(location.tenantId == tenantId) { "location belongs to another tenant" }
        return assets.save(SerializedAsset(UUID.randomUUID(), tenantId, skuId, serialNumber.trim(), macAddress?.uppercase(), InventoryStatus.AVAILABLE, location.id, CustodyClaim(custodyOwnerId, OwnerKind.WAREHOUSE, location.id)))
    }
}
