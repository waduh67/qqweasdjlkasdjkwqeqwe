package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseMasterState
import com.duluin.ftth.inventory.WarehouseTracking
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

@MappedSuperclass
abstract class WarehouseVersionedEntity(id: UUID) : TenantAwareJpaEntity(id) {
    @Version
    @Column(nullable = false)
    var revision: Long = 0
}

@Entity
@Table(name = "inventory_sku")
class WarehouseSkuJpaEntity(
    id: UUID,
    @Column(nullable = false) var code: String,
    @Column(nullable = false) var name: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var tracking: WarehouseTracking,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var baseUnit: WarehouseBaseUnit,
    @Column(nullable = false, columnDefinition = "text[]") var allowedOwnershipModes: Array<String> = arrayOf("LOAN", "SALE"),
    @Column(nullable = false) var inspectionRequired: Boolean = true,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: WarehouseMasterState = WarehouseMasterState.ACTIVE,
) : WarehouseVersionedEntity(id)

@Entity
@Table(name = "inventory_supplier")
class WarehouseSupplierJpaEntity(
    id: UUID,
    @Column(nullable = false) var code: String,
    @Column(nullable = false) var name: String,
    var contactReference: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: WarehouseMasterState = WarehouseMasterState.ACTIVE,
) : WarehouseVersionedEntity(id)

@Entity
@Table(name = "inventory_uom_conversion")
class WarehouseUomConversionJpaEntity(
    id: UUID,
    @Column(nullable = false) var skuId: UUID,
    @Column(nullable = false) var packageUnit: String,
    @Column(nullable = false) var numerator: Long,
    @Column(nullable = false) var denominator: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: WarehouseMasterState = WarehouseMasterState.ACTIVE,
) : WarehouseVersionedEntity(id)
