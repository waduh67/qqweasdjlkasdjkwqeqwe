package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.domain.model.CustodyClaim
import com.duluin.ftth.inventory.domain.model.InventoryLocation
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.LocationKind
import com.duluin.ftth.inventory.domain.model.OwnerKind
import com.duluin.ftth.inventory.domain.model.SerializedAsset
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Entity
@Table(name = "inventory_location")
class InventoryLocationJpaEntity(
    id: UUID,
    @Column(nullable = false, length = 64) var code: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) var kind: LocationKind,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "inventory_serialized_asset")
class SerializedAssetJpaEntity(
    id: UUID,
    @Column(nullable = false) var skuId: UUID,
    @Column(nullable = false, length = 128, updatable = false) var serialNumber: String,
    @Column(length = 17, updatable = false) var macAddress: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) var status: InventoryStatus,
    @Column(nullable = false) var locationId: UUID,
    @Column(nullable = false) var custodyOwnerId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) var custodyOwnerKind: OwnerKind,
    @Column var installedOnuId: UUID?,
    @Column(length = 128) var lastOperationKey: String?,
) : TenantAwareJpaEntity(id)

interface InventoryLocationJpaRepository : JpaRepository<InventoryLocationJpaEntity, UUID> {
    fun findAllByTenantId(tenantId: UUID): List<InventoryLocationJpaEntity>
}
interface SerializedAssetJpaRepository : JpaRepository<SerializedAssetJpaEntity, UUID> {
    fun findAllByTenantId(tenantId: UUID): List<SerializedAssetJpaEntity>
    fun findByTenantIdAndSerialNumber(tenantId: UUID, serialNumber: String): SerializedAssetJpaEntity?
    fun findByTenantIdAndMacAddress(tenantId: UUID, macAddress: String): SerializedAssetJpaEntity?
    fun findByTenantIdAndLastOperationKey(tenantId: UUID, key: String): SerializedAssetJpaEntity?
}

@Component
class InventoryLocationPersistenceAdapter(private val repository: InventoryLocationJpaRepository) : InventoryLocationRepository {
    override fun findById(id: UUID): InventoryLocation? = repository.findById(id).orElse(null)?.toDomain()
    override fun findAll(tenantId: UUID): List<InventoryLocation> = repository.findAllByTenantId(tenantId).map { it.toDomain() }
    override fun save(location: InventoryLocation): InventoryLocation = repository.save(location.toEntity()).toDomain()
    private fun InventoryLocationJpaEntity.toDomain() = InventoryLocation(id, tenantId!!, code, kind)
    private fun InventoryLocation.toEntity() = InventoryLocationJpaEntity(id, code, kind)
}

@Component
class SerializedAssetPersistenceAdapter(private val repository: SerializedAssetJpaRepository) : SerializedAssetRepository {
    override fun findById(id: UUID): SerializedAsset? = repository.findById(id).orElse(null)?.toDomain()
    override fun findAll(tenantId: UUID): List<SerializedAsset> = repository.findAllByTenantId(tenantId).map { it.toDomain() }
    override fun findBySerial(tenantId: UUID, serialNumber: String): SerializedAsset? = repository.findByTenantIdAndSerialNumber(tenantId, serialNumber)?.toDomain()
    override fun findByMac(tenantId: UUID, macAddress: String): SerializedAsset? = repository.findByTenantIdAndMacAddress(tenantId, macAddress)?.toDomain()
    override fun save(asset: SerializedAsset): SerializedAsset = repository.save(asset.toEntity()).toDomain()
    override fun existsHistoricalSerial(tenantId: UUID, serialNumber: String): Boolean = repository.findByTenantIdAndSerialNumber(tenantId, serialNumber) != null
    override fun existsHistoricalMac(tenantId: UUID, macAddress: String): Boolean = repository.findByTenantIdAndMacAddress(tenantId, macAddress) != null
    override fun findByOperation(tenantId: UUID, operationKey: String): SerializedAsset? = repository.findByTenantIdAndLastOperationKey(tenantId, operationKey)?.toDomain()
    private fun SerializedAssetJpaEntity.toDomain() = SerializedAsset(id, tenantId!!, skuId, serialNumber, macAddress, status, locationId, CustodyClaim(custodyOwnerId, custodyOwnerKind, locationId), installedOnuId)
    private fun SerializedAsset.toEntity() = SerializedAssetJpaEntity(id, skuId, serialNumber, macAddress, status, locationId, custody.ownerId, custody.ownerKind, installedOnuId, null)
}
