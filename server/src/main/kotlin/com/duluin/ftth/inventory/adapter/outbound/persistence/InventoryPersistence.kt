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
    // V181.
    @Column var parentId: UUID? = null,
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
    fun findByTenantIdAndCode(tenantId: UUID, code: String): InventoryLocationJpaEntity?
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
    override fun findByCode(tenantId: UUID, code: String): InventoryLocation? =
        repository.findByTenantIdAndCode(tenantId, code.trim().uppercase())?.toDomain()
    /**
     * Baris yang sudah ada DIMUAT lalu diubah; hanya baris baru yang disisipkan.
     *
     * Menyimpan instance entity baru dengan id lama TIDAK meng-update: [TenantAwareJpaEntity]
     * mewarisi `Persistable.isNew()` yang selalu true untuk objek yang belum pernah dimuat,
     * jadi Spring Data memanggil `persist` dan Postgres menolaknya dengan duplicate key.
     * Sebelum ada jalur ubah di sini, kesalahan itu belum pernah muncul karena lokasi memang
     * belum pernah bisa diubah siapa pun.
     *
     * Jalur sisipan mengembalikan [location] apa adanya, BUKAN hasil `toDomain()`: Hibernate
     * baru mengisi `@TenantId` saat INSERT-nya di-flush, jadi entity yang baru lahir masih
     * bertenant null dan `tenantId!!` meledak NPE.
     */
    override fun save(location: InventoryLocation): InventoryLocation {
        val existing = repository.findById(location.id).orElse(null)
            ?: run {
                repository.save(location.toEntity())
                return location
            }
        existing.code = location.code
        existing.kind = location.kind
        existing.parentId = location.parentId
        return repository.save(existing).toDomain()
    }
    private fun InventoryLocationJpaEntity.toDomain() = InventoryLocation(id, tenantId!!, code, kind, parentId)
    private fun InventoryLocation.toEntity() = InventoryLocationJpaEntity(id, code, kind, parentId)
}

@Component
class SerializedAssetPersistenceAdapter(private val repository: SerializedAssetJpaRepository) : SerializedAssetRepository {
    override fun findById(id: UUID): SerializedAsset? = repository.findById(id).orElse(null)?.toDomain()
    override fun findAll(tenantId: UUID): List<SerializedAsset> = repository.findAllByTenantId(tenantId).map { it.toDomain() }
    override fun findBySerial(tenantId: UUID, serialNumber: String): SerializedAsset? = repository.findByTenantIdAndSerialNumber(tenantId, serialNumber)?.toDomain()
    override fun findByMac(tenantId: UUID, macAddress: String): SerializedAsset? = repository.findByTenantIdAndMacAddress(tenantId, macAddress)?.toDomain()
    /**
     * Baris yang sudah ada DIMUAT lalu diubah, bukan dibuat ulang: [SerializedAssetJpaEntity]
     * mewarisi `Persistable.isNew()` yang selalu `true` untuk instance baru, jadi menyimpan
     * objek baru dengan id lama membuat Hibernate memanggil `persist` dan gagal dengan
     * duplicate key — bukan meng-update seperti yang diharapkan pemanggil.
     */
    override fun save(asset: SerializedAsset, operationKey: String?): SerializedAsset {
        val existing = repository.findById(asset.id).orElse(null)
            // Jalur sisipan mengembalikan [asset] apa adanya: `@TenantId` baru terisi saat INSERT
            // di-flush, jadi `toDomain()` di sini akan menabrak `tenantId!!` yang masih null.
            ?: run {
                repository.save(asset.toEntity(operationKey))
                return asset
            }
        existing.skuId = asset.skuId
        existing.status = asset.status
        existing.locationId = asset.locationId
        existing.custodyOwnerId = asset.custody.ownerId
        existing.custodyOwnerKind = asset.custody.ownerKind
        existing.installedOnuId = asset.installedOnuId
        // Kunci lama hanya ditimpa kalau pemanggil membawa kunci baru; menimpanya dengan null
        // akan menghapus jejak idempotency operasi sebelumnya.
        if (operationKey != null) existing.lastOperationKey = operationKey
        return repository.save(existing).toDomain()
    }
    /**
     * `deleteById` diam saja kalau barisnya sudah tidak ada — dan itu memang yang diinginkan:
     * pembatalan restock bisa dijalankan dua kali (penolakan menyusul penyapu tenggat), dan
     * percobaan kedua tidak boleh menggagalkan keputusan approval yang sah.
     */
    override fun delete(assetId: UUID) = repository.deleteById(assetId)
    override fun existsHistoricalSerial(tenantId: UUID, serialNumber: String): Boolean = repository.findByTenantIdAndSerialNumber(tenantId, serialNumber) != null
    override fun existsHistoricalMac(tenantId: UUID, macAddress: String): Boolean = repository.findByTenantIdAndMacAddress(tenantId, macAddress) != null
    override fun findByOperation(tenantId: UUID, operationKey: String): SerializedAsset? = repository.findByTenantIdAndLastOperationKey(tenantId, operationKey)?.toDomain()
    private fun SerializedAssetJpaEntity.toDomain() = SerializedAsset(id, tenantId!!, skuId, serialNumber, macAddress, status, locationId, CustodyClaim(custodyOwnerId, custodyOwnerKind, locationId), installedOnuId)
    private fun SerializedAsset.toEntity(operationKey: String?) =
        SerializedAssetJpaEntity(id, skuId, serialNumber, macAddress, status, locationId, custody.ownerId, custody.ownerKind, installedOnuId, operationKey)
}
