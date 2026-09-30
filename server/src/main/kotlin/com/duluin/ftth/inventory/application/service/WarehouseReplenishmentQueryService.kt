package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReplenishmentQuery
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReplenishmentStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.inbound.LocationSnapshot
import com.duluin.ftth.inventory.application.port.inbound.MasterKind
import com.duluin.ftth.inventory.application.port.inbound.SkuSnapshot
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.LocationKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class WarehouseReplenishmentQueryService(private val access: ReplenishmentAccess, private val store: ReplenishmentStore,
    private val query: ReplenishmentQuery, private val evaluator: ReplenishmentEvaluator, private val masters: WarehouseMasterStore,
    private val masterAccess: WarehouseMasterService, private val scopes: InventoryWarehouseScopeApi) : InventoryReplenishmentQueryApi {
    override fun rules(page: Int, size: Int, locationId: UUID?, skuId: UUID?, active: Boolean?): WarehousePage<WarehouseReplenishmentRuleView> {
        page(page, size)
        val current = access.reader()
        masters.lockTopology()
        locationId?.let { location(it, current) }
        val (ids, total) = query.ruleIds(access.query(current), page, size, locationId, skuId, false, active = active)
        return WarehousePage(ids.map { references(store.rule(it), current) }, page, size, total)
    }

    override fun requests(page: Int, size: Int, locationId: UUID?, skuId: UUID?, state: ReplenishmentState?): WarehousePage<WarehouseReplenishmentRequestView> {
        page(page, size)
        val current = access.reader()
        masters.lockTopology()
        locationId?.let { location(it, current) }
        val (ids, total) = query.ruleIds(access.query(current), page, size, locationId, skuId, true, state = state)
        return WarehousePage(ids.map {
            val request = store.request(it)
            val view = references(store.rule(request.ruleId), current)
            WarehouseReplenishmentRequestView(request, view.sku, view.location)
        }, page, size, total)
    }

    override fun rule(id: UUID): WarehouseReplenishmentDetails {
        val current = access.reader()
        masters.lockTopology()
        val rule = store.rule(id)
        val view = references(rule, current)
        return details(view, store.pending(id), current)
    }

    override fun request(id: UUID): WarehouseReplenishmentDetails {
        val current = access.reader()
        masters.lockTopology()
        val request = store.request(id)
        return details(references(store.rule(request.ruleId), current), request, current)
    }

    private fun references(rule: ReplenishmentRule, current: CurrentAuthority): WarehouseReplenishmentRuleView {
        val location = location(rule.locationId, current)
        val sku = masters.get(MasterKind.SKU, rule.skuId) as SkuSnapshot
        return WarehouseReplenishmentRuleView(rule, WarehouseReplenishmentSku(sku.id, sku.code, sku.name, sku.state),
            WarehouseReplenishmentLocation(location.id, location.code, location.name, location.state,
                location.state == WarehouseMasterState.ACTIVE && location.issueEligible && location.kind in setOf(LocationKind.WAREHOUSE, LocationKind.BIN)))
    }

    private fun location(id: UUID, current: CurrentAuthority): LocationSnapshot {
        val location = masters.get(MasterKind.LOCATION, id) as LocationSnapshot
        masterAccess.authorizeLocation(location, current, scopes.currentUnderFence(current.fence))
        return location
    }

    private fun details(view: WarehouseReplenishmentRuleView, request: ReplenishmentRequest?, current: CurrentAuthority): WarehouseReplenishmentDetails {
        val position = query.position(view.rule, access.query(current))
        val eligible = view.rule.active && view.sku.state == WarehouseMasterState.ACTIVE && view.location.replenishmentEligible
        return WarehouseReplenishmentDetails(view.rule, view.sku, view.location, position,
            (if (eligible) evaluator.quantity(view.rule, position) else 0L).toString(), request)
    }

    private fun page(page: Int, size: Int) { if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
}
