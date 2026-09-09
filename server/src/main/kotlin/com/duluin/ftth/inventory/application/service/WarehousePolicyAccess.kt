package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.*
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class WarehousePolicyAccess(private val scopes: InventoryWarehouseScopeApi, private val masters: WarehouseMasterStore,
    private val masterService: WarehouseMasterService, private val directory: ApprovalAuthorityApi, private val store: WarehousePolicyPersistence) {
    fun permission(current: CurrentAuthority, permission: String) {
        if (!current.platformAdmin && permission !in current.permissions) masterFailure(WarehouseErrorCode.FORBIDDEN)
    }
    fun location(id: UUID, current: CurrentAuthority): LocationSnapshot {
        val location = masters.get(MasterKind.LOCATION, id) as LocationSnapshot
        masterService.authorizeLocation(location, current, scopes.currentUnderFence(current.fence))
        if (location.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.NOT_FOUND)
        return location
    }
    fun directory(current: CurrentAuthority): ApprovalAuthorityDirectory = directory.directory(current.fence)
    fun scopeSetupLocation(id: UUID, current: CurrentAuthority, initialGrant: Boolean): Boolean {
        val scope = scopes.currentUnderFence(current.fence)
        val setup = initialGrant && !current.platformAdmin && scope is AuthorityScope.Restricted && scope.ids.isEmpty() &&
            store.scopes(current.fence.identity.userId).isEmpty() && store.current() == null &&
            setOf("iam.user.assign", "iam.role.create", "inventory.approval.manage", "inventory.location.manage").all(current.permissions::contains)
        if (!setup) { location(id, current); return false }
        val location = masters.get(MasterKind.LOCATION, id) as LocationSnapshot
        masterService.authorizeLocation(location, current, AuthorityScope.Restricted(setOf(id)))
        if (location.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.NOT_FOUND)
        return true
    }
    fun eligible(principal: ApprovalPrincipal, locations: Collection<UUID>): Boolean {
        if ("inventory.approval.decide" !in principal.permissions) return false
        val scope = store.locationsFor(principal.id)
        return locations.all { id ->
            val location = masters.get(MasterKind.LOCATION, id) as LocationSnapshot
            id in scope && location.areaId in principal.areaIds && location.state == WarehouseMasterState.ACTIVE
        }
    }
    fun replay(prior: com.duluin.ftth.inventory.adapter.outbound.persistence.SettingsReplay?, current: CurrentAuthority,
        hash: String, cutover: Long): String? {
        if (prior == null) return null
        if (prior.actor != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        prior.locations.forEach { location(it, current) }
        if (prior.hash != hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.cutoverEpoch != cutover) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        return prior.body
    }
}
