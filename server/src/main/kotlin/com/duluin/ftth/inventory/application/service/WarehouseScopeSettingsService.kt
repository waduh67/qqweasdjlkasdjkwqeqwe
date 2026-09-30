package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class WarehouseScopeSettingsService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val access: WarehousePolicyAccess, private val store: WarehousePolicyPersistence) {
    private val mapper = jacksonObjectMapper()
    @Transactional
    fun list(user: UUID): List<WarehouseScopeGrant> {
        val current = authority.lockCurrent()
        access.permission(current, "inventory.location.view")
        if (access.directory(current).users.none { it.id == user }) masterFailure(WarehouseErrorCode.NOT_FOUND)
        return store.scopes(user).onEach { access.location(it.locationId, current) }
    }
    @Transactional(rollbackFor = [Exception::class])
    fun replace(user: UUID, location: UUID, input: WarehouseScopeInput, key: String): String {
        receiptKey(key)
        if (input.expectedRevision !in 0 until Long.MAX_VALUE) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val change = authority.lockForChange()
        val current = authority.lockCurrent()
        access.permission(current, "inventory.location.manage")
        val bootstrap = access.scopeSetupLocation(location, current, input.active && input.expectedRevision == 0L && user == current.fence.identity.userId)
        if (access.directory(current).users.none { it.id == user }) masterFailure(WarehouseErrorCode.NOT_FOUND)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("user" to user, "location" to location, "input" to input)))
        access.replay(store.replay("scope.replace", key), current, canonical.hash, cutover.snapshot.epoch)?.let { return it }
        val prior = store.scopes(user).singleOrNull { it.locationId == location }
        if ((prior?.revision ?: 0) != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val epoch = change.incrementEpoch()
        val grant = store.scope(user, location, input.active, current.fence.identity.userId, epoch)
        val body = mapper.writeValueAsString(grant)
        store.record("scope.replace", key, current.fence.identity.userId, grant.id, listOf(location), grant.revision,
            canonical.hash, epoch, cutover.snapshot.epoch, body, bootstrap)
        return body
    }
}
