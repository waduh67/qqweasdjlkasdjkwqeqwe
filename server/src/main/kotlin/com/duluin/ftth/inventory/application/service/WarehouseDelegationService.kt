package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.UUID

@Service
class WarehouseDelegationService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val access: WarehousePolicyAccess, private val store: WarehousePolicyPersistence) {
    private val mapper = jacksonObjectMapper()
    @Transactional
    fun list(): List<WarehouseDelegation> {
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.view")
        return store.delegations().onEach { access.location(it.locationId, current) }
    }
    @Transactional(rollbackFor = [Exception::class])
    fun create(input: WarehouseDelegationInput, key: String): String {
        receiptKey(key)
        if (input.expectedRevision != 0L || input.approverId == input.delegateId) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val change = authority.lockForChange()
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.manage")
        access.location(input.locationId, current)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        access.replay(store.replay("delegation.create", key), current, canonical.hash, cutover.snapshot.epoch)?.let { return it }
        val now = store.now()
        if (input.validUntil <= now || input.validUntil > now.plus(Duration.ofDays(30))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val principals = access.directory(current).users
        val source = principals.singleOrNull { it.id == input.approverId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        val delegate = principals.singleOrNull { it.id == input.delegateId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        if (!access.eligible(source, listOf(input.locationId)) || !access.eligible(delegate, listOf(input.locationId)) ||
            (input.sourceRoleId != null && input.sourceRoleId !in source.roleIds)) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        val policy = store.current() ?: masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        val rule = policy.rules.singleOrNull { it.operation == input.operation } ?: masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        if (!store.covered(input.locationId, policy.warehouseIds) || rule.tiers.none { tier ->
            if (input.sourceRoleId != null) input.sourceRoleId in tier.roleIds else source.id in tier.userIds
        }) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        val live = store.delegations().filter { it.revokedAt == null && it.validUntil > now }
        if (live.any { it.delegateId == input.approverId || it.approverId == input.delegateId })
            masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED, "Delegation chains and cycles are not allowed")
        val epoch = change.incrementEpoch()
        val delegation = WarehouseDelegation(UUID.randomUUID(), input.approverId, input.delegateId, input.sourceRoleId,
            input.locationId, input.operation, now, input.validUntil, null, 1)
        store.delegate(delegation, current.fence.identity.userId, epoch)
        val body = mapper.writeValueAsString(delegation)
        store.record("delegation.create", key, current.fence.identity.userId, delegation.id, listOf(input.locationId), 1,
            canonical.hash, epoch, cutover.snapshot.epoch, body)
        return body
    }
    @Transactional(rollbackFor = [Exception::class])
    fun revoke(id: UUID, input: WarehouseRevisionInput, key: String): String {
        receiptKey(key)
        if (input.expectedRevision !in 1 until Long.MAX_VALUE) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val change = authority.lockForChange()
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.manage")
        val grant = store.delegations().singleOrNull { it.id == id } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        access.location(grant.locationId, current)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "input" to input)))
        access.replay(store.replay("delegation.revoke", key), current, canonical.hash, cutover.snapshot.epoch)?.let { return it }
        if (grant.revision != input.expectedRevision || grant.revokedAt != null) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val epoch = change.incrementEpoch()
        store.revoke(id, current.fence.identity.userId, epoch)
        val result = store.delegations().single { it.id == id }
        val body = mapper.writeValueAsString(result)
        store.record("delegation.revoke", key, current.fence.identity.userId, id, listOf(grant.locationId), result.revision,
            canonical.hash, epoch, cutover.snapshot.epoch, body)
        return body
    }
}
