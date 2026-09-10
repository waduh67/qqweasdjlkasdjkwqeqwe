package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class WarehouseApprovalAuthority(private val access: WarehousePolicyAccess, private val policy: WarehousePolicyPersistence) {
    fun authorize(record: WarehouseApprovalRecord, tier: Int, current: CurrentAuthority,
        decisions: List<WarehouseApprovalDecisionRecord>, now: Instant): WarehouseDelegation? {
        access.permission(current, "inventory.approval.view")
        access.permission(current, "inventory.approval.decide")
        val actor = current.fence.identity.userId
        val evaluation = record.snapshot.evaluation
        val excluded = evaluation.excludedUserIds
        val directory = access.directory(current)
        val principal = directory.users.singleOrNull { it.id == actor } ?: masterFailure(WarehouseErrorCode.FORBIDDEN)
        val locations = record.snapshot.locations
        if (actor in excluded || !access.eligible(principal, locations)) masterFailure(WarehouseErrorCode.FORBIDDEN)
        val grants = policy.delegations().filter { it.revokedAt == null && it.validFrom <= now && it.validUntil > now && it.operation == evaluation.operation }
        if (grants.any { it.delegateId == actor && it.approverId in excluded }) masterFailure(WarehouseErrorCode.FORBIDDEN)
        val requirement = requireNotNull(evaluation.policy).rules.single { it.operation == evaluation.operation }.tiers[tier - 1]
        val decidingRoles = requirement.roleIds.filter { "inventory.approval.decide" in directory.roles[it].orEmpty() }
        val used = decisions.flatMap { listOfNotNull(it.actorId, it.delegation?.approverId) }.toSet()
        if (actor in used) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (actor in requirement.userIds || principal.roleIds.any { it in decidingRoles }) return null
        val delegation = grants.sortedBy { it.id.toString() }.firstOrNull { grant ->
            val delegator = directory.users.singleOrNull { it.id == grant.approverId }
            grant.delegateId == actor && grant.approverId !in excluded && grant.approverId !in used && delegator != null &&
                access.eligible(delegator, locations) && locations.filter { policy.covered(it, requireNotNull(evaluation.policy).warehouseIds) }
                    .all { policy.covered(it, listOf(grant.locationId)) } &&
                (if (grant.sourceRoleId == null) grant.approverId in requirement.userIds
                else grant.sourceRoleId in decidingRoles && grant.sourceRoleId in delegator.roleIds) &&
                grants.none { it.delegateId == grant.approverId || it.approverId == grant.delegateId }
        } ?: masterFailure(WarehouseErrorCode.FORBIDDEN)
        return delegation
    }
    fun view(record: WarehouseApprovalRecord, current: CurrentAuthority) {
        access.permission(current, "inventory.approval.view")
        record.snapshot.locations.forEach { access.location(it, current) }
    }
}
