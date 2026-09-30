package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReplenishmentQuery
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReplenishmentStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockUnit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class WarehouseReplenishmentService(private val access: ReplenishmentAccess, private val store: ReplenishmentStore,
    private val query: ReplenishmentQuery, private val evaluation: ReplenishmentEvaluator) : WarehouseReplenishmentApi {
    private val mapper = jacksonObjectMapper()

    override fun saveRule(id: UUID?, input: ReplenishmentRuleInput, key: String): ReplenishmentCommandResult {
        if ((id == null) != (input.expectedRevision == null)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        input.expectedRevision?.let(::validRevision)
        val command = access.begin(if (id == null) "rule.create" else "rule.update", key,
            mapper.writeValueAsString(mapOf("id" to id, "input" to input)), true)
        command.replay?.let { return it }
        val existing = id?.let { store.rule(it) }
        existing?.let {
            access.location(it.locationId, command.current)
            if (it.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
            if (it.skuId != input.skuId || it.locationId != input.locationId || it.baseUnit != input.baseUnit)
                masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Rule identity cannot change")
        }
        val rule = ReplenishmentRule(id ?: UUID.randomUUID(), existing?.revision?.let { Math.addExact(it, 1) } ?: 0,
            input.skuId, input.locationId, input.baseUnit, input.minimumBase, input.maximumBase, input.targetBase,
            input.packageMultipleBase, input.leadTimeDays, true)
        evaluation.need(rule)
        access.eligible(rule, command.current)
        store.save(rule, existing == null)
        return access.finish(command, rule.id, null, rule.revision, mapper.writeValueAsString(rule))
    }

    override fun archiveRule(id: UUID, input: ReplenishmentRevision, key: String): ReplenishmentCommandResult {
        validRevision(input.expectedRevision)
        val command = access.begin("rule.archive", key, payload(id, input.expectedRevision), true)
        command.replay?.let { return it }
        val rule = store.rule(id)
        access.location(rule.locationId, command.current)
        revision(rule.revision, input.expectedRevision)
        if (store.pending(id)?.acceptedAt != null) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Cancel the accepted request before archiving")
        store.pending(id)?.let { store.terminal(it.id, ReplenishmentState.CANCELLED) }
        val archived = rule.copy(active = false, revision = Math.addExact(rule.revision, 1))
        store.save(archived, false)
        return access.finish(command, id, null, archived.revision, mapper.writeValueAsString(archived))
    }

    override fun recompute(id: UUID, input: ReplenishmentRevision, key: String): ReplenishmentCommandResult {
        validRevision(input.expectedRevision)
        val command = access.begin("rule.recompute", key, payload(id, input.expectedRevision))
        command.replay?.let { return it }
        val rule = store.rule(id)
        access.eligible(rule, command.current)
        revision(rule.revision, input.expectedRevision)
        val result = evaluation.recompute(rule, access.query(command.current))
        val body = result.request?.let(mapper::writeValueAsString) ?: mapper.writeValueAsString(result)
        return access.finish(command, id, result.request?.id, result.request?.revision ?: rule.revision, body)
    }

    override fun accept(id: UUID, input: ReplenishmentAcceptance, key: String): ReplenishmentCommandResult {
        validRevision(input.expectedRevision)
        validRevision(input.expectedRuleRevision)
        val command = access.begin("request.accept", key, mapper.writeValueAsString(mapOf("id" to id, "input" to input)))
        command.replay?.let { return it }
        val request = store.request(id)
        val rule = store.rule(request.ruleId)
        access.eligible(rule, command.current)
        val requested = StockQuantity.parseBase(input.quantityBase, StockUnit.valueOf(rule.baseUnit.name)).quantityBase
        revision(request.revision, input.expectedRevision)
        revision(rule.revision, input.expectedRuleRevision)
        if (!rule.active || request.state != ReplenishmentState.PENDING || request.acceptedAt != null || request.ruleRevision != rule.revision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val position = query.position(rule, access.query(command.current))
        val quantity = evaluation.quantity(rule, position)
        if (quantity == 0L || quantity != requested || request.quantityBase.toLong() != requested)
            masterFailure(WarehouseErrorCode.STALE_REVISION, "Supply changed; recompute and confirm the current suggestion")
        store.refresh(request, rule, position, quantity)
        store.accept(id, command.current.fence.identity.userId)
        val accepted = store.request(id)
        return access.finish(command, rule.id, id, accepted.revision, mapper.writeValueAsString(accepted))
    }

    override fun cancel(id: UUID, input: ReplenishmentRevision, key: String): ReplenishmentCommandResult {
        validRevision(input.expectedRevision)
        val command = access.begin("request.cancel", key, payload(id, input.expectedRevision))
        command.replay?.let { return it }
        val request = store.request(id)
        access.location(store.rule(request.ruleId).locationId, command.current)
        revision(request.revision, input.expectedRevision)
        if (request.state != ReplenishmentState.PENDING) masterFailure(WarehouseErrorCode.STALE_REVISION)
        store.terminal(id, ReplenishmentState.CANCELLED)
        val cancelled = store.request(id)
        return access.finish(command, request.ruleId, id, cancelled.revision, mapper.writeValueAsString(cancelled))
    }

    override fun bindReceiving(id: UUID, input: ReplenishmentReceivingReference, key: String): ReplenishmentCommandResult {
        validRevision(input.expectedRevision)
        validRevision(input.documentRevision)
        val command = access.begin("request.receiving", key, mapper.writeValueAsString(mapOf("id" to id, "input" to input)))
        command.replay?.let { return it }
        val request = store.request(id)
        val rule = store.rule(request.ruleId)
        access.eligible(rule, command.current)
        revision(request.revision, input.expectedRevision)
        if (request.state != ReplenishmentState.PENDING || request.acceptedAt == null || request.receivingLineId != null)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val (location, quantity) = query.receiving(rule, input)
        access.location(location, command.current)
        if (quantity != request.quantityBase.toLong()) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Receipt quantity must match the accepted request")
        store.bind(id, input)
        val bound = store.request(id)
        return access.finish(command, rule.id, id, bound.revision, mapper.writeValueAsString(bound))
    }

    override fun rule(id: UUID): ReplenishmentRule {
        val current = access.reader()
        return store.rule(id).also { access.location(it.locationId, current) }
    }

    override fun request(id: UUID): ReplenishmentRequest {
        val current = access.reader()
        return store.request(id).also { access.location(store.rule(it.ruleId).locationId, current) }
    }

    override fun rules(page: Int, size: Int, locationId: UUID?, skuId: UUID?): WarehousePage<ReplenishmentRule> {
        page(page, size)
        val current = access.reader()
        val (ids, total) = query.ruleIds(access.query(current), page, size, locationId, skuId, false)
        return WarehousePage(ids.map(store::rule), page, size, total)
    }

    override fun requests(page: Int, size: Int, locationId: UUID?, skuId: UUID?): WarehousePage<ReplenishmentRequest> {
        page(page, size)
        val current = access.reader()
        val (ids, total) = query.ruleIds(access.query(current), page, size, locationId, skuId, true)
        return WarehousePage(ids.map(store::request), page, size, total)
    }

    override fun history(ruleId: UUID, page: Int, size: Int): List<ReplenishmentHistory> {
        page(page, size)
        rule(ruleId)
        return store.history(ruleId, page, size)
    }

    private fun payload(id: UUID, revision: Long) = mapper.writeValueAsString(mapOf("id" to id, "expectedRevision" to revision))
    private fun validRevision(value: Long) { if (value !in 0 until Long.MAX_VALUE) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
    private fun revision(actual: Long, expected: Long) { if (actual != expected) masterFailure(WarehouseErrorCode.STALE_REVISION) }
    private fun page(page: Int, size: Int) { if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
}
