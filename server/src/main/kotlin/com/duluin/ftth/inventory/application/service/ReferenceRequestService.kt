package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceRequestStore
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.LocationKind
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional
class ReferenceRequestService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val owners: TenantOwnerStore, private val scopes: InventoryWarehouseScopeApi, private val iam: IamApi,
    private val masters: WarehouseMasterStore, private val catalog: WarehouseMasterService, private val store: ReferenceRequestStore,
    private val sites: SiteReferenceApi) {
    private val mapper = jacksonObjectMapper()
    internal data class Access(val current: CurrentAuthority, val cutover: TenantCutoverFence, val scope: AuthorityScope)

    fun submit(input: ReferenceRequestInput, key: String): WarehouseOperationReceipt {
        val access = access(key)
        val actor = access.current.fence.identity.userId
        val managing = permits(access.current, "warehouse.request.review")
        if (!managing) {
            permission(access.current, "warehouse.request.own")
            if (input.warehouseId != null || input.technicianId != null && input.technicianId != actor) masterFailure(WarehouseErrorCode.FORBIDDEN)
        }
        if (input.warehouseId != null && input.technicianId != null || input.lines.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        receiptText(input.reason, 1000)
        val technician = if (input.warehouseId == null) input.technicianId ?: actor else null
        val destination = input.warehouseId?.let { warehouse(it, access) }
        if (destination != null && input.kind != ReferenceRequestKind.PROCUREMENT) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val user = technician?.let { id -> iam.findUser(id)?.takeIf { it.active && it.technician }
            ?: masterFailure(WarehouseErrorCode.NOT_FOUND) }
        val canonical = canonical(null, input)
        replay("SUBMIT", key, canonical, access)?.let { return it }
        val lines = input.lines.map { line ->
            positiveReceiptQuantity(line.requestedBase, line.baseUnit)
            if ((line.skuId == null) == (line.proposedName == null)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val sku = line.skuId?.let { sku(it, line.baseUnit) }
            line.proposedName?.let { receiptText(it, 200) }
            if (input.kind == ReferenceRequestKind.RESTOCK && sku == null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            ReferenceRequestLineView(UUID.randomUUID(), line.baseUnit, line.requestedBase, sku?.id,
                sku?.name ?: requireNotNull(line.proposedName), line.proposedName)
        }
        val now = referenceTimestamp()
        val requester = iam.findUser(actor) ?: masterFailure(WarehouseErrorCode.FORBIDDEN)
        store.lockSettings()
        val policy = store.settings()
        val view = ReferenceRequestView(UUID.randomUUID(), 0, input.kind, ReferenceRequestState.SUBMITTED, actor, requester.name,
            destination?.id, destination?.let { it.name ?: it.code }, user?.id, user?.name, policy.requireManagerApproval, policy.revision,
            input.reason, lines, now, now)
        store.save(view, true)
        return record("SUBMIT", key, canonical, access, view, input.reason, if (managing) "warehouse.request.review" else "warehouse.request.own")
    }

    fun review(id: UUID, input: ReferenceRequestReview, key: String): WarehouseOperationReceipt {
        val access = access(key)
        permission(access.current, "warehouse.request.review")
        val canonical = canonical(id, input)
        replay("REVIEW", key, canonical, access)?.let { return it }
        val prior = editable(id, input.expectedRevision, access)
        if (prior.state != ReferenceRequestState.SUBMITTED || input.lines.size != prior.lines.size ||
            input.lines.map { it.lineId }.toSet() != prior.lines.map { it.id }.toSet()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        notes(input.notes)
        val choices = input.lines.associateBy { it.lineId }
        val lines = prior.lines.map { line ->
            val choice = choices.getValue(line.id)
            val quantity = quantity(choice.approvedBase, line.baseUnit)
            if (quantity > line.requestedBase.toLong()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            if (line.skuId != null && choice.skuId != null && choice.skuId != line.skuId) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val mapped = (choice.skuId ?: line.skuId)?.let { sku(it, line.baseUnit) }
            if (quantity > 0 && mapped == null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Pilih barang katalog untuk material yang disetujui")
            line.copy(approvedBase = choice.approvedBase, skuId = mapped?.id, name = mapped?.name ?: line.name)
        }
        if (lines.none { it.approvedBase.toLong() > 0 }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Sisakan minimal satu material")
        val view = prior.copy(revision = prior.revision + 1, lines = lines, updatedAt = referenceTimestamp(),
            state = if (prior.requiresManagerApproval) ReferenceRequestState.MANAGER_REVIEW else ReferenceRequestState.APPROVED)
        store.save(view, false)
        return record("REVIEW", key, canonical, access, view, input.notes, "warehouse.request.review")
    }

    fun decide(id: UUID, input: ReferenceRequestDecision, key: String): WarehouseOperationReceipt {
        val access = access(key)
        val canonical = canonical(id, input)
        replay("DECIDE", key, canonical, access)?.let { return it }
        val prior = authorized(store.get(id, true), access)
        val decisionPermission = if (prior.state == ReferenceRequestState.SUBMITTED) "warehouse.request.review" else "warehouse.request.approve"
        permission(access.current, decisionPermission)
        if (prior.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (prior.state !in setOf(ReferenceRequestState.SUBMITTED, ReferenceRequestState.MANAGER_REVIEW) ||
            input.approved && prior.state != ReferenceRequestState.MANAGER_REVIEW) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if (!input.approved) receiptText(input.reason, 1000) else notes(input.reason)
        val view = prior.copy(revision = prior.revision + 1, updatedAt = referenceTimestamp(),
            state = if (input.approved) ReferenceRequestState.APPROVED else ReferenceRequestState.REJECTED)
        store.save(view, false)
        return record("DECIDE", key, canonical, access, view, input.reason, decisionPermission)
    }

    fun detail(id: UUID): ReferenceRequestDetail {
        val access = readAccess()
        val view = authorized(store.get(id), access)
        return ReferenceRequestDetail(view, store.timeline(id))
    }

    fun list(page: Int, size: Int, state: ReferenceRequestState?, search: String?): WarehousePage<ReferenceRequestView> {
        if (page < 0 || size !in 1..100 || search != null && search.length > 200) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val access = readAccess()
        val all = permits(access.current, "warehouse.request.view")
        if (!all) permission(access.current, "warehouse.request.own")
        val warehouses = if (all) visibleWarehouses(access) else null
        return store.list(page, size, state, search, if (all) null else access.current.fence.identity.userId,
            if (all) warehouses else null, all)
    }

    fun settings(): ReferenceOperationalSettings {
        val current = authority.lockCurrent()
        owner(current)
        return store.settings()
    }

    fun settings(input: ReferenceOperationalSettingsInput, key: String): WarehouseOperationReceipt {
        val access = access(key)
        owner(access.current)
        if (input.overdueDays !in 1..365) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val canonical = canonical(null, input)
        replay("SETTINGS", key, canonical, access)?.let { return it }
        store.lockSettings()
        val prior = store.settings()
        if (prior.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val view = ReferenceOperationalSettings(prior.revision + 1, input.requireManagerApproval, input.overdueDays)
        store.saveSettings(view)
        return store.command("SETTINGS", key, access.cutover.snapshot.tenantId, view.revision, access.current.fence.identity.userId,
            access.current.fence.epoch, access.cutover.snapshot.epoch, canonical.json, canonical.hash, view, "Pengaturan operasional", "OWNER")
    }

    internal fun access(key: String): Access {
        receiptKey(key)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.REFERENCE_STOCK)
        val current = authority.lockCurrent()
        masters.lockTopology()
        return Access(current, cutover, if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence))
    }
    internal fun readAccess(): Access {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        masters.lockTopology()
        return Access(current, cutover, if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence))
    }
    internal fun authorized(view: ReferenceRequestView, access: Access): ReferenceRequestView {
        if (!permits(access.current, "warehouse.request.view")) {
            permission(access.current, "warehouse.request.own")
            if (view.technicianId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.NOT_FOUND)
        }
        view.warehouseId?.let { warehouse(it, access) }
        return view
    }
    internal fun editable(id: UUID, revision: Long, access: Access): ReferenceRequestView = authorized(store.get(id, true), access).also {
        if (it.revision != revision) masterFailure(WarehouseErrorCode.STALE_REVISION)
    }
    internal fun warehouse(id: UUID, access: Access): LocationSnapshot {
        val location = masters.get(MasterKind.LOCATION, id, true) as LocationSnapshot
        catalog.authorizeLocation(location, access.current, access.scope)
        if (location.state != WarehouseMasterState.ACTIVE || location.kind !in setOf(LocationKind.WAREHOUSE, LocationKind.BIN) || !location.issueEligible)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return location
    }
    internal fun sku(id: UUID, unit: WarehouseBaseUnit): SkuSnapshot = (masters.get(MasterKind.SKU, id, true) as SkuSnapshot).also {
        if (it.state != WarehouseMasterState.ACTIVE || it.baseUnit != unit) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Satuan material harus sama")
    }
    internal fun canonical(id: UUID?, input: Any): WarehouseCanonicalPayload = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "input" to input)))
    internal fun replay(action: String, key: String, canonical: WarehouseCanonicalPayload, access: Access): WarehouseOperationReceipt? {
        val prior = store.lockKey(action, key) ?: return null
        if (prior.permission == "OWNER") owner(access.current) else permission(access.current, prior.permission)
        if (prior.actorId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.epoch != access.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        if (action != "SETTINGS") authorized(store.get(prior.resourceId), access)
        return prior.receipt
    }
    internal fun record(action: String, key: String, canonical: WarehouseCanonicalPayload, access: Access, view: ReferenceRequestView,
        notes: String, permission: String, movement: UUID? = null): WarehouseOperationReceipt = store.command(action, key, view.id, view.revision,
        access.current.fence.identity.userId, access.current.fence.epoch, access.cutover.snapshot.epoch, canonical.json, canonical.hash, view, notes, permission, movement)
    internal fun visibleWarehouses(access: Access): Set<UUID> {
        val areas = if (access.current.platformAdmin) AuthorityScope.Unrestricted else access.current.areaScope
        val visibleSites = sites.visibleAreas(areas)
        val ids = mutableSetOf<UUID>()
        var page = 0
        do {
            val result = masters.list(MasterKind.LOCATION, MasterFilter(page = page, size = 100,
                state = WarehouseMasterState.ACTIVE, locationKinds = setOf(LocationKind.WAREHOUSE, LocationKind.BIN)),
                access.scope, areas, visibleSites)
            ids += result.items.map { it.id }
            page++
        } while (page.toLong() * 100 < result.totalElements)
        return ids
    }
    internal fun permission(current: CurrentAuthority, code: String) { if (!permits(current, code)) masterFailure(WarehouseErrorCode.FORBIDDEN) }
    private fun permits(current: CurrentAuthority, code: String) = current.platformAdmin || code in current.permissions
    private fun owner(current: CurrentAuthority) { if (owners.findUserId() != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN) }
    internal fun notes(value: String) { if (value.length > 1000 || value.any { it.isISOControl() && it != '\n' }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
    internal fun quantity(value: String, unit: WarehouseBaseUnit): Long = com.duluin.ftth.inventory.domain.model.StockQuantity.parseBase(value,
        com.duluin.ftth.inventory.domain.model.StockUnit.valueOf(unit.name)).quantityBase
}
