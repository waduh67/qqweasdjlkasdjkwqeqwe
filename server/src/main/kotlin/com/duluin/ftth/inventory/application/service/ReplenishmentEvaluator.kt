package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReplenishmentQuery
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReplenishmentStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.inventory.domain.model.ReplenishmentNeed
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockUnit
import org.springframework.stereotype.Component

@Component
class ReplenishmentEvaluator(private val store: ReplenishmentStore, private val query: ReplenishmentQuery) {
    fun need(rule: ReplenishmentRule): ReplenishmentNeed {
        val unit = StockUnit.valueOf(rule.baseUnit.name)
        return ReplenishmentNeed(StockQuantity.parseBase(rule.minimumBase, unit), StockQuantity.parseBase(rule.maximumBase, unit),
            StockQuantity.parseBase(rule.targetBase, unit), StockQuantity.parseBase(rule.packageMultipleBase, unit), rule.leadTimeDays)
    }

    fun quantity(rule: ReplenishmentRule, position: ReplenishmentPosition) = need(rule)
        .quantity(position.availableBase.toBigInteger(), position.confirmedInboundBase.toBigInteger()).quantityBase

    fun recompute(rule: ReplenishmentRule, access: WarehouseQueryAccess): ReplenishmentEvaluation {
        val position = query.position(rule, access)
        val pending = store.pending(rule.id)
        val quantity = if (rule.active) quantity(rule, position) else 0L
        if (quantity == 0L) {
            if (pending != null) store.terminal(pending.id, if (rule.active) ReplenishmentState.FULFILLED else ReplenishmentState.CANCELLED)
            return ReplenishmentEvaluation(rule.id, position, pending?.let { store.request(it.id) })
        }
        if (pending == null) return ReplenishmentEvaluation(rule.id, position, store.suggest(rule, position, quantity))
        if (pending.acceptedAt == null && (pending.ruleSnapshot != rule || pending.quantityBase != quantity.toString() ||
                pending.availableBase != position.availableBase || pending.reservedBase != position.reservedBase ||
                pending.confirmedInboundBase != position.confirmedInboundBase)) store.refresh(pending, rule, position, quantity)
        return ReplenishmentEvaluation(rule.id, position, store.request(pending.id))
    }
}
