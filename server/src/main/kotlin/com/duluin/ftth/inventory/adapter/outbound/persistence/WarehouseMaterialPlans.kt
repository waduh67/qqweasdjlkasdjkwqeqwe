package com.duluin.ftth.inventory.adapter.outbound.persistence

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
@Table(name = "inventory_material_plan")
class WarehouseMaterialPlanJpaEntity(
    id: UUID,
    @Column(nullable = false) var workOrderId: UUID,
    @Column(nullable = false) var planRevision: Long,
    @Column(nullable = false) var workOrderRevision: Long,
    @Column(nullable = false) var materialMode: String,
    @Column(nullable = false) var actorId: UUID,
    var reason: String? = null,
    @Column(nullable = false) var state: String = "DRAFT",
    var submittedAt: Instant? = null,
) : WarehouseVersionedEntity(id)

@Entity
@Table(name = "inventory_material_plan_line")
class WarehouseMaterialPlanLineJpaEntity(
    id: UUID,
    @Column(nullable = false) var planId: UUID,
    @Column(nullable = false) var lineNumber: Int,
    @Column(nullable = false) var skuId: UUID,
    @Column(nullable = false) var quantityBase: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var baseUnit: WarehouseBaseUnit,
    @Column(nullable = false) var continuousCut: Boolean = true,
) : WarehouseVersionedEntity(id)

@Entity
@Immutable
@Table(name = "inventory_usage_snapshot")
class WarehouseUsageSnapshotJpaEntity(
    id: UUID,
    @Column(nullable = false) val workOrderId: UUID,
    @Column(nullable = false) val useRevision: Long,
    @Column(nullable = false) val planId: UUID,
    @Column(nullable = false) val workOrderRevision: Long,
    @Column(nullable = false) val operationId: UUID,
    @Column(nullable = false, columnDefinition = "uuid[]") val postingIds: Array<UUID>,
    @Column(nullable = false, columnDefinition = "text") val frozenSnapshot: String,
    val compensatesSnapshotId: UUID? = null,
) : WarehouseVersionedEntity(id)
