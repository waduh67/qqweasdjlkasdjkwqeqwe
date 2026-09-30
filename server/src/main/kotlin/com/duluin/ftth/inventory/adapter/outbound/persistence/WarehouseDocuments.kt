package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseDocumentKind
import com.duluin.ftth.inventory.WarehouseTracking
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inventory_document")
class WarehouseDocumentJpaEntity(
    id: UUID,
    @Column(nullable = false) var code: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var kind: WarehouseDocumentKind,
    @Column(nullable = false) var actorId: UUID,
    @Column(nullable = false) var cutoverEpoch: Long,
    @Column(nullable = false) var authorityEpoch: Long,
    @Column(nullable = false) var state: String = "DRAFT",
    var workOrderId: UUID? = null,
    var customerId: UUID? = null,
    var supplierId: UUID? = null,
    var sourceDocumentId: UUID? = null,
    var sourceRevision: Long? = null,
    var workOrderRevision: Long? = null,
    var planRevision: Long? = null,
    var useRevision: Long? = null,
    var customerLabelSnapshot: String? = null,
    var workOrderCodeSnapshot: String? = null,
    var sourceReference: String? = null,
    var reason: String? = null,
    var migrationBatchId: UUID? = null,
    var submittedAt: Instant? = null,
    var closedAt: Instant? = null,
) : WarehouseVersionedEntity(id)

@Entity
@Table(name = "inventory_document_line")
class WarehouseDocumentLineJpaEntity(
    id: UUID,
    @Column(nullable = false) var documentId: UUID,
    @Column(nullable = false) var documentRevision: Long,
    @Column(nullable = false) var lineNumber: Int,
    @Column(nullable = false) var skuId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var baseUnit: WarehouseBaseUnit,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var tracking: WarehouseTracking,
    @Column(nullable = false) var quantityBase: Long,
    var stockIdentityId: UUID? = null,
    var lotId: UUID? = null,
    var sourceLineId: UUID? = null,
    @Column(nullable = false) var continuousCut: Boolean = true,
    var locationId: UUID? = null,
    var destinationLocationId: UUID? = null,
    var custodianId: UUID? = null,
    var custodianKind: String? = null,
    var condition: String? = null,
    var legalOwner: String? = null,
    @Column(nullable = false) var acceptedBase: Long = 0,
    @Column(nullable = false) var rejectedBase: Long = 0,
    @Column(nullable = false) var missingBase: Long = 0,
    var costTotalMinor: Long? = null,
    var costBasisQuantityBase: Long? = null,
    var currency: String? = null,
    var conversionNumerator: Long? = null,
    var conversionDenominator: Long? = null,
    var packageQuantity: Long? = null,
    @Column(nullable = false) var inspectionRequiredSnapshot: Boolean = true,
) : WarehouseVersionedEntity(id)
