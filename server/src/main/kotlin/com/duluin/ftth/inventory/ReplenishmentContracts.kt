package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

data class ReplenishmentRuleInput(
    val skuId: UUID, val locationId: UUID, val baseUnit: WarehouseBaseUnit,
    val minimumBase: String, val maximumBase: String, val targetBase: String,
    val packageMultipleBase: String, val leadTimeDays: Int, val expectedRevision: Long? = null,
)

data class ReplenishmentRevision(val expectedRevision: Long)
data class ReplenishmentAcceptance(val expectedRevision: Long, val expectedRuleRevision: Long, val quantityBase: String)
data class ReplenishmentReceivingReference(val expectedRevision: Long, val documentId: UUID, val documentRevision: Long, val lineId: UUID)

data class ReplenishmentRule(
    val id: UUID, val revision: Long, val skuId: UUID, val locationId: UUID, val baseUnit: WarehouseBaseUnit,
    val minimumBase: String, val maximumBase: String, val targetBase: String,
    val packageMultipleBase: String, val leadTimeDays: Int, val active: Boolean,
)

enum class ReplenishmentState { PENDING, FULFILLED, CANCELLED }
data class ReplenishmentPosition(val availableBase: String, val reservedBase: String, val confirmedInboundBase: String)
data class ReplenishmentRequest(
    val id: UUID, val revision: Long, val ruleId: UUID, val ruleRevision: Long,
    val state: ReplenishmentState, val quantityBase: String, val baseUnit: WarehouseBaseUnit,
    val availableBase: String, val reservedBase: String, val confirmedInboundBase: String,
    val ruleSnapshot: ReplenishmentRule, val acceptedAt: Instant?, val acceptedBy: UUID?,
    val receivingDocumentId: UUID?, val receivingLineId: UUID?, val createdAt: Instant,
)

data class ReplenishmentEvaluation(val ruleId: UUID, val position: ReplenishmentPosition, val request: ReplenishmentRequest?)
data class ReplenishmentCommandResult(val status: Int, val body: String)
data class ReplenishmentHistory(val id: UUID, val action: String, val revision: Long, val createdAt: Instant)

interface WarehouseReplenishmentApi {
    fun saveRule(id: UUID?, input: ReplenishmentRuleInput, key: String): ReplenishmentCommandResult
    fun archiveRule(id: UUID, input: ReplenishmentRevision, key: String): ReplenishmentCommandResult
    fun rule(id: UUID): ReplenishmentRule
    fun rules(page: Int, size: Int, locationId: UUID?, skuId: UUID?): WarehousePage<ReplenishmentRule>
    fun recompute(id: UUID, input: ReplenishmentRevision, key: String): ReplenishmentCommandResult
    fun accept(id: UUID, input: ReplenishmentAcceptance, key: String): ReplenishmentCommandResult
    fun cancel(id: UUID, input: ReplenishmentRevision, key: String): ReplenishmentCommandResult
    fun bindReceiving(id: UUID, input: ReplenishmentReceivingReference, key: String): ReplenishmentCommandResult
    fun request(id: UUID): ReplenishmentRequest
    fun requests(page: Int, size: Int, locationId: UUID?, skuId: UUID?): WarehousePage<ReplenishmentRequest>
    fun history(ruleId: UUID, page: Int, size: Int): List<ReplenishmentHistory>
}
