package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseIdentityClaimState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.util.UUID

@Entity
@Table(name = "inventory_identity_claim")
class WarehouseIdentityClaimJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) val identityType: String,
    @Column(nullable = false, updatable = false, columnDefinition = "text") val canonicalValue: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: WarehouseIdentityClaimState,
    var admittedAssetId: UUID? = null,
) : WarehouseVersionedEntity(id)

@Entity
@Immutable
@Table(name = "inventory_identity_candidate")
class WarehouseIdentityCandidateJpaEntity(
    id: UUID,
    @Column(nullable = false) val identityType: String,
    @Column(nullable = false) val sourceTable: String,
    @Column(nullable = false) val sourceId: UUID,
    @Column(nullable = false, columnDefinition = "text") val rawValue: String,
    val claimId: UUID? = null,
    @Column(columnDefinition = "text") val canonicalValue: String? = null,
    @Column(columnDefinition = "text") val previousCanonicalValue: String? = null,
    val previousClaimId: UUID? = null,
) : WarehouseVersionedEntity(id)

@Entity
@Table(name = "inventory_warehouse_scope")
class WarehouseScopeJpaEntity(
    id: UUID,
    @Column(nullable = false) var userId: UUID,
    @Column(nullable = false) var locationId: UUID,
    @Column(nullable = false) var grantedBy: UUID,
    @Column(nullable = false) var authorityEpoch: Long,
    @Column(nullable = false) var state: String = "ACTIVE",
) : WarehouseVersionedEntity(id)
