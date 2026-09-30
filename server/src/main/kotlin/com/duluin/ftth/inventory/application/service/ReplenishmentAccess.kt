package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReplenishmentStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.LocationKind
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Component
import java.util.UUID

data class ReplenishmentCommand(val action: String, val key: String, val hash: String, val epoch: Long,
    val current: CurrentAuthority, val replay: ReplenishmentCommandResult?)

@Component
class ReplenishmentAccess(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val policy: WarehousePolicyAccess, private val scopes: InventoryWarehouseScopeApi, private val sites: SiteReferenceApi,
    private val masters: WarehouseMasterStore, private val store: ReplenishmentStore) {

    fun begin(action: String, key: String, payload: String, controlPlane: Boolean = false): ReplenishmentCommand {
        receiptKey(key)
        store.deadline()
        val fence = cutovers.lockForCommand(cutovers.read().epoch,
            if (controlPlane) WarehouseOperationClass.CONTROL_PLANE else WarehouseOperationClass.ORDINARY_STOCK)
        authority.lockForChange().assertHeld()
        val current = reader(true)
        masters.lockTopology()
        val hash = WarehouseCanonicalPayload.parse(payload).hash
        val prior = store.replay(action, key)
        val replay = prior?.let {
            if (it.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            policy.location(store.rule(it.ruleId).locationId, current)
            if (it.hash != hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (it.epoch != fence.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            ReplenishmentCommandResult(200, it.body)
        }
        return ReplenishmentCommand(action, key, hash, fence.snapshot.epoch, current, replay)
    }

    fun reader(mutate: Boolean = false): CurrentAuthority {
        store.deadline()
        return authority.lockCurrent().also {
            policy.permission(it, if (mutate) "inventory.request.manage" else "inventory.request.view")
        }
    }

    fun location(id: UUID, current: CurrentAuthority) = policy.location(id, current)

    fun eligible(rule: ReplenishmentRule, current: CurrentAuthority) {
        val location = policy.location(rule.locationId, current)
        if (!location.issueEligible || location.kind !in setOf(LocationKind.WAREHOUSE, LocationKind.BIN))
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Replenishment requires an issue-eligible warehouse or bin")
        val sku = masters.get(MasterKind.SKU, rule.skuId) as SkuSnapshot
        if (sku.state != WarehouseMasterState.ACTIVE || sku.baseUnit != rule.baseUnit)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Active SKU and matching base unit required")
    }

    fun query(current: CurrentAuthority): WarehouseQueryAccess {
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
    }

    fun finish(command: ReplenishmentCommand, rule: UUID, request: UUID?, revision: Long, body: String): ReplenishmentCommandResult {
        store.record(command.action, command.key, command.current.fence.identity.userId, rule, request, revision, command.hash, command.epoch, body)
        return ReplenishmentCommandResult(200, body)
    }
}
