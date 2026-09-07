package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseAdmission
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
@Table(name = "inventory_customer_material_fact")
class WarehouseMaterialFactJpaEntity(
    id: UUID,
    @Column(nullable = false) val customerId: UUID,
    @Column(nullable = false) val workOrderId: UUID,
    @Column(nullable = false) val itemCategory: String,
    @Column(nullable = false) val installed: Boolean,
    @Column(nullable = false) val returned: Boolean,
    @Column(nullable = false) val recordedAt: Instant,
    @Column(nullable = false) val operationKey: String,
    @Column(nullable = false) val payloadHash: String,
    @Column(name = "quantity") val legacyQuantity: Int? = null,
    val quantityBase: Long? = null,
    val baseUnit: String? = null,
    val stockIdentityId: UUID? = null,
    val lotId: UUID? = null,
    val postingId: UUID? = null,
    val useRevision: Long? = null,
    val compensationId: UUID? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val warehouseAdmission: WarehouseAdmission = WarehouseAdmission.VERIFIED,
) : WarehouseVersionedEntity(id)
