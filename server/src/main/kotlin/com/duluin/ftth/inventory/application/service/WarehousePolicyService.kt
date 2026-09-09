package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper

@Service
class WarehousePolicyService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val access: WarehousePolicyAccess, private val store: WarehousePolicyPersistence) {
    private val mapper = jacksonObjectMapper()

    @Transactional
    fun current(): WarehousePolicyVersion? {
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.view")
        return store.current()?.also { it.warehouseIds.forEach { id -> access.location(id, current) } }
    }
    @Transactional
    fun history(page: Int, size: Int): List<WarehousePolicyVersion> {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.view")
        return store.history(page, size).onEach { version -> version.warehouseIds.forEach { access.location(it, current) } }
    }
    @Transactional(rollbackFor = [Exception::class])
    fun replace(input: WarehousePolicyInput, key: String): String {
        receiptKey(key)
        validate(input)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val change = authority.lockForChange()
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.manage")
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        access.replay(store.replay("policy.replace", key), current, canonical.hash, cutover.snapshot.epoch)?.let { return it }
        val previous = store.current()
        if (input.expectedRevision != (previous?.revision ?: 0L)) masterFailure(WarehouseErrorCode.STALE_REVISION,
            "Expected policy revision ${input.expectedRevision}; current revision ${previous?.revision ?: 0L}")
        (input.warehouseIds + previous?.warehouseIds.orEmpty()).distinct().forEach { access.location(it, current) }
        val directory = access.directory(current)
        val delegatedByActor = store.delegations().filter { it.approverId == current.fence.identity.userId && it.revokedAt == null && it.validUntil > store.now() }
            .map { it.delegateId }.toSet()
        input.rules.forEach { rule -> rule.tiers.forEach { tier ->
            if (tier.userIds.any { id -> directory.users.none { it.id == id && "inventory.approval.decide" in it.permissions } } ||
                tier.roleIds.any { "inventory.approval.decide" !in directory.roles[it].orEmpty() })
                masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED, "Configure active approver users or roles with approval.decide")
            val independent = directory.users.filter { it.id != current.fence.identity.userId && it.id !in delegatedByActor &&
                (it.id in tier.userIds || it.roleIds.any(tier.roleIds::contains)) }
            if (independent.none { access.eligible(it, input.warehouseIds) })
                masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED, "Grant an independent approver the policy warehouse and area scope")
        } }
        val epoch = change.incrementEpoch()
        val version = WarehousePolicyVersion(java.util.UUID.randomUUID(), input.expectedRevision + 1, input.currency, input.expiryHours,
            input.warehouseIds, input.rules, current.fence.identity.userId, epoch, store.now())
        val body = mapper.writeValueAsString(version)
        store.save(version, WarehouseCanonicalPayload.parse(body).hash)
        store.record("policy.replace", key, version.actorId, version.id, input.warehouseIds, version.revision, canonical.hash,
            epoch, cutover.snapshot.epoch, body)
        return body
    }
    private fun validate(input: WarehousePolicyInput) {
        fun invalid(): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if (input.expectedRevision !in 0 until Long.MAX_VALUE || !input.currency.matches(Regex("[A-Z]{3}")) || input.expiryHours !in 1..720 ||
            input.warehouseIds.isEmpty() || input.warehouseIds.size > 100 || input.warehouseIds.distinct().size != input.warehouseIds.size ||
            input.rules.isEmpty() || input.rules.size > PolicyOperation.entries.size || input.rules.map { it.operation }.distinct().size != input.rules.size) invalid()
        input.rules.forEach { rule ->
            if (rule.tiers.isEmpty() || rule.tiers.size > 10) invalid()
            var previous = java.math.BigInteger.ZERO
            rule.tiers.forEach { tier ->
                if (!tier.minimumMinor.matches(Regex("[1-9][0-9]{0,37}"))) invalid()
                val amount = tier.minimumMinor.toBigInteger()
                if (amount <= previous || tier.userIds.size + tier.roleIds.size !in 1..100 ||
                    tier.userIds.distinct().size != tier.userIds.size || tier.roleIds.distinct().size != tier.roleIds.size) invalid()
                previous = amount
            }
        }
    }
}
