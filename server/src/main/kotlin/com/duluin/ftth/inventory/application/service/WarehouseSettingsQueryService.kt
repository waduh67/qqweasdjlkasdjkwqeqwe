package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseSettingsQueryService(private val authority: CurrentAuthorityApi, private val cutovers: InventoryTenantCutoverApi,
    private val access: WarehousePolicyAccess, private val scopes: InventoryWarehouseScopeApi, private val sites: SiteReferenceApi,
    private val masters: WarehouseMasterStore, private val masterAccess: WarehouseMasterService, private val store: WarehousePolicyPersistence,
    private val query: WarehouseSettingsQuery, private val references: WarehouseApprovalQuery, private val users: IamApi) : InventorySettingsQueryApi {
    override fun history(page: WarehousePageRequest): WarehousePage<WarehousePolicyDetails> {
        val current = begin(page)
        val found = query.history(page, scope(current))
        val roleNames = access.directory(current).roleNames
        val names = names(found.items.flatMap { policy -> listOf(policy.actorId) + policy.rules.flatMap { rule -> rule.tiers.flatMap { it.userIds } } }.toSet())
        return WarehousePage(found.items.map { policy ->
            val tiers = policy.rules.flatMap { it.tiers }
            WarehousePolicyDetails(true, policy, WarehousePolicyReferences((tiers.flatMap { it.userIds } + policy.actorId).distinct().mapNotNull(names::get),
                tiers.flatMap { it.roleIds }.distinct().mapNotNull { id -> roleNames[id]?.let { WarehousePolicyChoice(id, it) } }, references.locations(policy.warehouseIds.toSet())))
        }, found.page, found.size, found.totalElements)
    }
    override fun delegations(page: WarehousePageRequest, locationId: UUID?, state: String?): WarehousePage<WarehouseDelegationView> {
        if (state != null && state !in setOf("ACTIVE", "EXPIRED", "REVOKED")) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = begin(page)
        locationId?.let { location(it, current) }
        val found = query.delegations(page, scope(current), locationId, state)
        val names = names(found.items.flatMap { listOf(it.delegation.approverId, it.delegation.delegateId) }.toSet())
        val roles = access.directory(current).roleNames
        val locations = references.locations(found.items.map { it.delegation.locationId }.toSet()).associateBy { it.id }
        return WarehousePage(found.items.map { row ->
            val grant = row.delegation
            WarehouseDelegationView(grant, row.state, names[grant.approverId], names[grant.delegateId],
                grant.sourceRoleId?.let { id -> roles[id]?.let { WarehousePolicyChoice(id, it) } }, locations.getValue(grant.locationId))
        }, found.page, found.size, found.totalElements)
    }
    override fun candidates(page: WarehousePageRequest, locationId: UUID, operation: PolicyOperation, kind: String,
        approverId: UUID?, sourceRoleId: UUID?, query: String?): WarehousePage<WarehousePolicyChoice> {
        if (kind !in setOf("APPROVER", "DELEGATE") || (kind == "DELEGATE") != (approverId != null) || (query != null && (query.isBlank() || query.length > 200))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = begin(page, manage = true)
        access.location(locationId, current)
        val policy = store.current() ?: masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        val rule = policy.rules.singleOrNull { it.operation == operation } ?: masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        if (!store.covered(locationId, policy.warehouseIds) || (sourceRoleId != null && rule.tiers.none { sourceRoleId in it.roleIds })) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        val directory = access.directory(current)
        val eligible = directory.users.filter { access.eligible(it, listOf(locationId)) }
        val now = store.now()
        val live = store.delegations().filter { it.revokedAt == null && it.validUntil > now }
        val sources = eligible.filter { person -> live.none { it.delegateId == person.id } && if (sourceRoleId == null) rule.tiers.any { person.id in it.userIds }
            else sourceRoleId in person.roleIds && "inventory.approval.decide" in directory.roles[sourceRoleId].orEmpty() }
        if (approverId != null && sources.none { it.id == approverId }) masterFailure(WarehouseErrorCode.NOT_FOUND)
        val selected = if (kind == "APPROVER") sources else eligible.filter { person -> person.id != approverId && live.none { it.approverId == person.id } }
        val choices = names(selected.map { it.id }.toSet()).values.filter { query == null || it.name.contains(query, ignoreCase = true) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id.toString() }))
        val start = (page.page.toLong() * page.size).coerceAtMost(choices.size.toLong()).toInt()
        return WarehousePage(choices.drop(start).take(page.size), page.page, page.size, choices.size.toLong())
    }
    private fun begin(page: WarehousePageRequest, manage: Boolean = false): CurrentAuthority {
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        access.permission(current, if (manage) "inventory.approval.manage" else "inventory.approval.view")
        masters.lockTopology()
        return current
    }
    private fun scope(current: CurrentAuthority): WarehouseQueryAccess {
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
    }
    private fun location(id: UUID, current: CurrentAuthority) {
        masterAccess.authorizeLocation(masters.get(MasterKind.LOCATION, id) as LocationSnapshot, current, scopes.currentUnderFence(current.fence))
    }
    private fun names(ids: Set<UUID>) = users.usersByIds(ids).associate { it.id to WarehousePolicyChoice(it.id, it.name) }
}
