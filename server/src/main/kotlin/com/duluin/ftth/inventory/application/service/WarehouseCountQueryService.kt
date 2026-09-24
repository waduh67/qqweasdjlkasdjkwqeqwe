package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseCountQueryService(private val counts: InventoryCountApi, private val query: WarehouseCountQuery,
    private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val access: WarehousePolicyAccess, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val sites: SiteReferenceApi,
    private val users: IamApi, private val policy: WarehousePolicyPersistence) : InventoryCountQueryApi {

    override fun list(filter: WarehouseCountFilter): WarehousePage<WarehouseCountDetails> {
        validateCountFilter(filter)
        val current = current("inventory.count.view")
        val page = query.ids(filter, visibility(current), current.fence.identity.userId)
        val views = page.items.map(counts::get)
        val names = names(views)
        return WarehousePage(views.map { WarehouseCountDetails(it, query.references(it, names)) }, page.page, page.size, page.totalElements)
    }

    override fun details(id: UUID): WarehouseCountDetails {
        val view = counts.get(id)
        return WarehouseCountDetails(view, query.references(view, names(listOf(view))))
    }

    override fun review(id: UUID): WarehouseCountReviewDetails {
        // The owner requires approval.view and submitted state, without count or stock read permission.
        val review = counts.review(id)
        return WarehouseCountReviewDetails(review, query.references(review.count, names(listOf(review.count))))
    }

    override fun positions(filter: WarehouseCountFilter): WarehousePage<WarehouseCountPositionOption> {
        validateCountFilter(filter)
        if (filter.locationId == null || filter.state != null || filter.from != null || filter.until != null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = current("inventory.count.view", "inventory.count.manage")
        access.location(filter.locationId, current)
        return query.positions(filter, visibility(current))
    }

    override fun counters(locationId: UUID, page: WarehousePageRequest, query: String?): WarehousePage<WarehouseCountPersonRef> {
        validateCountFilter(WarehouseCountFilter(page.page, page.size, query = query))
        val current = current("inventory.count.view", "inventory.count.manage")
        val location = access.location(locationId, current)
        val actor = current.fence.identity.userId
        val eligible = access.directory(current).users.filter { person ->
            setOf("inventory.count.view", "inventory.count.manage").all { it in person.permissions } &&
                (person.id == actor || locationId in policy.locationsFor(person.id) && location.areaId in person.areaIds)
        }.map { it.id }.toSet()
        val people = users.usersByIds(eligible).filter { it.active && (query == null || it.name.contains(query, ignoreCase = true)) }
            .sortedWith(compareBy({ it.name.lowercase(java.util.Locale.ROOT) }, { it.id.toString() }))
        val offset = page.page.toLong() * page.size
        val selected = if (offset >= people.size) emptyList() else people.drop(offset.toInt()).take(page.size)
        return WarehousePage(selected.map { WarehouseCountPersonRef(it.id, it.name) }, page.page, page.size, people.size.toLong())
    }

    override fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseCountHistoryEntry> {
        validateCountFilter(WarehouseCountFilter(page.page, page.size))
        counts.get(id)
        val actor = authority.lockCurrent().fence.identity.userId
        return query.history(id, page, actor.takeUnless { it == query.requester(id) })
    }

    private fun names(views: List<WarehouseCountView>): Map<UUID, String> {
        val ids = views.flatMap { view -> view.entries.map { it.counterId } + query.requester(view.id) }.toSet()
        return users.usersByIds(ids).associate { it.id to it.name }
    }
    private fun current(vararg permissions: String): CurrentAuthority {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        permissions.forEach { access.permission(current, it) }
        masters.lockTopology()
        return current
    }
    private fun visibility(current: CurrentAuthority): WarehouseQueryAccess {
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        return WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
    }
}

internal fun validateCountFilter(filter: WarehouseCountFilter) {
    if (filter.page < 0 || filter.size !in 1..100 || (filter.query != null && (filter.query.isBlank() || filter.query.length > 200)) ||
        (filter.from == null) != (filter.until == null) || (filter.from != null && filter.until != null &&
            (filter.from >= filter.until || Duration.between(filter.from, filter.until) > Duration.ofDays(366)))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}
