package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import java.time.Instant
import java.util.UUID

data class IssuePerson(val id: UUID, val name: String)
data class IssuePickedLine(val id: UUID, val demandLineId: UUID, val planLineId: UUID,
    val reservationId: UUID, val reservationRevision: Long, val dimension: PostingDimension,
    val sourceIdentityId: UUID, val quantityBase: String, val baseUnit: WarehouseBaseUnit,
    val sku: MaterialSkuSnapshot, val serial: String?, val lotCode: String?, val locationName: String,
    val substitution: MaterialSubstitution?, val originalSku: MaterialSkuSnapshot?)
data class IssueSnapshot(val issueId: UUID, val code: String, val revision: Long, val state: String,
    val workOrderId: UUID, val workOrderCode: String, val workOrderRevision: Long,
    val customerId: UUID?, val customerLabelSnapshot: String?, val demandDocumentId: UUID,
    val demandRevision: Long, val planId: UUID, val planRevision: Long,
    val sender: IssuePerson, val receiver: IssuePerson, val lines: List<IssuePickedLine>, val recordedAt: Instant)
data class PreparedIssuePick(val lines: List<IssuePickedLine>, val legs: List<PostingLeg>,
    val splits: List<PostingSplit>, val reservations: List<ReservationChange>, val candidates: List<ReservationCandidate>)
