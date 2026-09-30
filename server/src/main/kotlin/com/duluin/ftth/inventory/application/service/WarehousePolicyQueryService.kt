package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseApprovalQuery
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehousePolicyQueryService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val policies: WarehousePolicyService, private val access: WarehousePolicyAccess, private val store: WarehousePolicyPersistence,
    private val users: IamApi, private val query: WarehouseApprovalQuery, private val masters: WarehouseMasterStore) : InventoryPolicyQueryApi {
    override fun details(): WarehousePolicyDetails {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.view")
        masters.lockTopology()
        val policy = policies.current()
        val tiers = policy?.rules.orEmpty().flatMap { it.tiers }
        val names = users.usersByIds(tiers.flatMap { it.userIds }.toSet()).map { WarehousePolicyChoice(it.id, it.name) }
        val roleNames = access.directory(current).roleNames
        val roles = tiers.flatMap { it.roleIds }.distinct().mapNotNull { id -> roleNames[id]?.let { WarehousePolicyChoice(id, it) } }
        return WarehousePolicyDetails(policy != null, policy, WarehousePolicyReferences(names, roles, query.locations(policy?.warehouseIds.orEmpty().toSet())))
    }
    override fun approvers(locations: Set<UUID>, kind: String, query: String?, page: WarehousePageRequest): WarehousePage<WarehousePolicyChoice> {
        if (locations.isEmpty() || locations.size > 100 || kind !in setOf("USER", "ROLE") || page.page < 0 || page.size !in 1..100 ||
            (query != null && (query.isBlank() || query.length > 200))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.manage")
        masters.lockTopology()
        locations.sortedBy(UUID::toString).forEach { access.location(it, current) }
        val directory = access.directory(current)
        val excluded = store.delegations().filter { it.approverId == current.fence.identity.userId && it.revokedAt == null && it.validUntil > store.now() }
            .map { it.delegateId }.toSet() + current.fence.identity.userId
        val eligible = directory.users.filter { it.id !in excluded && access.eligible(it, locations) }
        val choices = if (kind == "USER") users.usersByIds(eligible.map { it.id }.toSet()).filter { it.active }.map { WarehousePolicyChoice(it.id, it.name) }
            else directory.roleNames.filter { (id, _) -> "inventory.approval.decide" in directory.roles[id].orEmpty() && eligible.any { id in it.roleIds } }
                .map { (id, name) -> WarehousePolicyChoice(id, name) }
        val matching = choices.filter { query == null || it.name.contains(query, ignoreCase = true) }.sortedWith(compareBy({ it.name.lowercase() }, { it.id.toString() }))
        val offset = (page.page.toLong() * page.size).coerceAtMost(matching.size.toLong()).toInt()
        return WarehousePage(matching.drop(offset).take(page.size), page.page, page.size, matching.size.toLong())
    }
}
