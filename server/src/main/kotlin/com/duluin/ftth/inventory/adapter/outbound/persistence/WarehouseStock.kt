package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseAdmission
import com.duluin.ftth.inventory.WarehouseBaseUnit
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "inventory_lot")
class WarehouseLotJpaEntity(
    id: UUID,
    @Column(nullable = false) val skuId: UUID,
    @Column(nullable = false) val code: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val baseUnit: WarehouseBaseUnit,
    @Column(nullable = false) val receivedQuantityBase: Long,
    @Column(nullable = false) val receivedAt: Instant,
    val supplierId: UUID? = null,
    val originDocumentLineId: UUID? = null,
    val costTotalMinor: Long? = null,
    val costBasisQuantityBase: Long? = null,
    val currency: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val warehouseAdmission: WarehouseAdmission = WarehouseAdmission.VERIFIED,
) : WarehouseVersionedEntity(id)

@Entity
@Table(name = "inventory_segment")
class WarehouseSegmentJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) val skuId: UUID,
    @Column(nullable = false, updatable = false) val kind: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val baseUnit: WarehouseBaseUnit,
    @Column(nullable = false, updatable = false) val quantityBase: Long,
    @Column(updatable = false) val lotId: UUID? = null,
    @Column(updatable = false) val assetId: UUID? = null,
    @Column(updatable = false) val parentSegmentId: UUID? = null,
    @Column(nullable = false) var state: String = "ACTIVE",
    @Enumerated(EnumType.STRING) @Column(nullable = false) var warehouseAdmission: WarehouseAdmission = WarehouseAdmission.VERIFIED,
) : WarehouseVersionedEntity(id)

@Entity
@Table(name = "inventory_reservation")
class WarehouseReservationJpaEntity(
    id: UUID,
    @Column(nullable = false) var documentLineId: UUID,
    @Column(nullable = false) var skuId: UUID,
    @Column(nullable = false) var stockIdentityId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var baseUnit: WarehouseBaseUnit,
    @Column(nullable = false) var locationId: UUID,
    @Column(nullable = false) var custodianId: UUID,
    @Column(nullable = false) var custodianKind: String,
    @Column(nullable = false) var condition: String,
    @Column(nullable = false) var legalOwner: String,
    @Column(nullable = false) var reservedUnpickedBase: Long,
    @Column(nullable = false) var reservedPickedBase: Long,
    @Column(nullable = false) var submittedAt: Instant,
    @Column(nullable = false) var expiresAt: Instant,
    var lotId: UUID? = null,
    @Column(nullable = false) var state: String = "OPEN",
) : WarehouseVersionedEntity(id)
