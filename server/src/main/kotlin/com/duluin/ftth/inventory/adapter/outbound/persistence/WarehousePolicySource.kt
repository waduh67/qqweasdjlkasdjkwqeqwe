package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import java.math.BigInteger
import java.util.UUID

data class PolicySourceLine(val locationId: UUID?, val custodianId: UUID?, val quantity: BigInteger,
    val numerator: BigInteger?, val denominator: BigInteger?, val currency: String?)
data class PolicySource(val id: UUID, val revision: Long, val operation: PolicyOperation, val requesterId: UUID,
    val counters: Set<UUID>, val lines: List<PolicySourceLine>, val titleCorrection: Boolean)

@Repository
class WarehousePolicySource(private val jdbc: WarehouseCommandJdbc) {
    fun lock(input: WarehouseSourceInput, action: PolicyOperation? = null): PolicySource = jdbc.execute { sql ->
        val header = sql.query("SELECT kind,actor_id,revision FROM inventory_document WHERE tenant_id=? AND id=? FOR NO KEY UPDATE", sql.tenant, input.sourceDocumentId) {
            Triple(it.getString("kind"), it.uuid("actor_id"), it.getLong("revision"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        if (header.third != input.sourceRevision) sql.fail(WarehouseErrorCode.STALE_REVISION)
        if (header.first == "COUNT" && sql.value("SELECT state FROM inventory_document WHERE tenant_id=? AND id=?", sql.tenant, input.sourceDocumentId)
            !in setOf("SUBMITTED", "APPROVED", "POSTED")) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val derived = when (header.first) {
            "RECEIPT" -> PolicyOperation.RECEIPT
            "ISSUE" -> PolicyOperation.ISSUE
            "OPENING_BALANCE" -> PolicyOperation.OPENING_BALANCE
            "ADJUSTMENT" -> PolicyOperation.ADJUSTMENT
            "LOSS" -> PolicyOperation.LOSS
            "SCRAP" -> PolicyOperation.SCRAP
            "COUNT" -> PolicyOperation.COUNT_VARIANCE
            "TITLE_CORRECTION", "RETURN_TITLE" -> PolicyOperation.TITLE_REACQUISITION
            "RETURN" -> if (action == PolicyOperation.TITLE_REACQUISITION) action else sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            else -> sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
        if (action != null && action != derived && !(derived == PolicyOperation.ISSUE && action == PolicyOperation.ISSUE_EXCEPTION))
            sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val operation = action ?: derived
        val lines = sql.query("""SELECT line.location_id,line.custodian_id,line.custodian_kind,
            CASE WHEN ? THEN (SELECT abs(observation.observed_quantity_base-observation.prior_quantity_base)
                FROM inventory_cycle_count observation JOIN inventory_count_entry entry ON entry.tenant_id=observation.tenant_id
                    AND entry.document_id=observation.document_id AND entry.balance_id=observation.balance_id
                WHERE observation.tenant_id=line.tenant_id AND entry.id=line.id AND observation.document_revision=
                    (SELECT max(document_revision) FROM inventory_count_round WHERE tenant_id=line.tenant_id AND document_id=line.document_id))
                ELSE line.quantity_base END quantity_base,
            coalesce(line.cost_total_minor,lot.cost_total_minor) numerator,
            coalesce(line.cost_basis_quantity_base,lot.cost_basis_quantity_base) denominator,
            coalesce(line.currency,lot.currency) currency FROM inventory_document_line line
            LEFT JOIN inventory_lot lot ON lot.tenant_id=line.tenant_id AND lot.id=line.lot_id
            WHERE line.tenant_id=? AND line.document_id=? ORDER BY line.line_number""", header.first == "COUNT", sql.tenant, input.sourceDocumentId) {
            PolicySourceLine(it.optionalUuid("location_id"), if (it.getString("custodian_kind") == "TECHNICIAN") it.optionalUuid("custodian_id") else null,
                it.getString("quantity_base").toBigInteger(), it.getString("numerator")?.toBigInteger(),
                it.getString("denominator")?.toBigInteger(), it.getString("currency"))
        }
        if (lines.isEmpty() || lines.any { it.locationId == null }) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val counters = sql.query("SELECT counter_id FROM inventory_cycle_count WHERE tenant_id=? AND document_id=? AND counter_id IS NOT NULL",
            sql.tenant, input.sourceDocumentId) { it.uuid("counter_id") }.toSet() + if (header.first == "TITLE_CORRECTION") sql.query("""SELECT handover.actor_id,request.customer_id
            FROM inventory_asset_title_request request JOIN inventory_asset_handover handover ON handover.tenant_id=request.tenant_id
            AND handover.id=request.handover_id WHERE request.tenant_id=? AND request.id=?""", sql.tenant, input.sourceDocumentId) {
            listOf(it.uuid("actor_id"), it.uuid("customer_id"))
        }.flatten() else emptyList()
        val transferParties = sql.query("""SELECT source.actor_id,source.transfer_receiver_id FROM inventory_document document
            JOIN inventory_document source ON source.tenant_id=document.tenant_id AND source.id=document.source_document_id
            WHERE document.tenant_id=? AND document.id=? AND document.transfer_remainder_action IS NOT NULL""", sql.tenant, input.sourceDocumentId) {
            listOf(it.uuid("actor_id"), it.uuid("transfer_receiver_id"))
        }.flatten()
        val returnParties = if (header.first == "RETURN_TITLE") sql.query("""SELECT snapshot::jsonb body FROM inventory_return_title_request
            WHERE tenant_id=? AND id=?""", sql.tenant, input.sourceDocumentId) {
            val body = tools.jackson.module.kotlin.jacksonObjectMapper().readTree(it.getString("body"))
            listOf("customerId", "handoverActorId", "removalActorId").map { key -> UUID.fromString(body.path("context").path(key).asString()) } +
                UUID.fromString(body.path("returned").path("view").path("receivedBy").asString())
        }.flatten() else emptyList()
        PolicySource(input.sourceDocumentId, header.third, operation, header.second, counters + transferParties + returnParties,
            lines, header.first in setOf("TITLE_CORRECTION", "RETURN_TITLE"))
    }
}
