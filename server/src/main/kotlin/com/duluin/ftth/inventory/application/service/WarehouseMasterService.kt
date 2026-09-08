package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.identity.MacIdentity
import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.domain.model.*
import com.duluin.ftth.network.NetworkApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class WarehouseMasterService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi, private val store: WarehouseMasterStore,
    private val operations: WarehouseOperationStore, private val iam: IamApi, private val network: NetworkApi) {
    private val mapper = jacksonObjectMapper()

    fun execute(kind: MasterKind, action: MasterAction, id: UUID?, input: MasterInput, key: String): WarehouseOperationReceipt {
        if (key.length !in 1..240 || key.any { it.code !in 33..126 }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val change = if (kind == MasterKind.LOCATION) authority.lockForChange() else null
        val current = authority.lockCurrent()
        permission(current, "${kind.permission}.manage")
        val allowed = scopes.currentUnderFence(current.fence)
        validate(input, action)
        val namespace = "warehouse.master.${kind.name.lowercase()}.${action.name.lowercase()}"
        val normalized = if (input is SkuInput) input.copy(allowedOwnershipModes = input.allowedOwnershipModes.sortedBy { it.name }.toSet(),
            minimumQuantityBase = input.minimumQuantityBase.toLong().toString()) else input
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "input" to normalized)))
        val preview = operations.findKey(namespace, key)
        val target = id ?: preview?.resourceId ?: UUID.randomUUID()
        if (preview != null && preview.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        val existing = if (id != null || preview != null) store.get(kind, target, true) else null
        if (existing is LocationSnapshot) authorizeLocation(existing, current, allowed)
        val prior = operations.lockKey(namespace, key)
        if (prior != null) {
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.hash != canonical.hash || (id != null && prior.resourceId != id)) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            if (kind == MasterKind.LOCATION) authorizeLocation(store.get(kind, prior.resourceId) as LocationSnapshot, current, allowed)
            return prior.receipt
        }
        if (input is LocationInput) validateLocation(target, input, current, allowed, existing == null)
        if (existing != null) {
            if (existing.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
            if (existing.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Master sudah diarsipkan")
            if (input is ArchiveMasterInput && store.hasReferences(kind, target)) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Selesaikan stok dan referensi terbuka sebelum arsip")
            if (existing is SkuSnapshot && input is SkuInput &&
                (existing.tracking != input.tracking || existing.baseUnit != input.baseUnit) && store.hasReferences(kind, target)) {
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Satuan dan tracking sudah digunakan")
            }
            if (existing is LocationSnapshot && input is LocationInput &&
                (existing.kind != input.kind || existing.parentLocationId != input.parentLocationId || existing.areaId != input.areaId ||
                 existing.siteId != input.siteId || existing.custodianId != input.custodianId || existing.issueEligible != input.issueEligible) && store.hasReferences(kind, target)) {
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Lokasi masih memiliki stok atau referensi")
            }
        }
        val result = store.save(kind, target, input, existing)
        if (kind == MasterKind.LOCATION) {
            val epoch = requireNotNull(change).incrementEpoch()
            if (existing == null) store.grantCreator(target, current.fence.identity.userId, epoch)
        }
        return operations.storeMaster(kind, action, key, target, result.revision, current.fence.identity.userId,
            current.fence.epoch, cutover.snapshot.epoch, canonical.json, canonical.hash, mapper.writeValueAsString(result), current.fence.identity.sessionId)
    }

    @Transactional
    fun get(kind: MasterKind, id: UUID): MasterSnapshot {
        val current = authority.lockCurrent(); permission(current, "${kind.permission}.view")
        val result = store.get(kind, id)
        if (result is LocationSnapshot) authorizeLocation(result, current, scopes.currentUnderFence(current.fence))
        return result
    }

    @Transactional
    fun list(kind: MasterKind, filter: MasterFilter): WarehousePage<MasterSnapshot> {
        val current = authority.lockCurrent(); permission(current, "${kind.permission}.view")
        if (filter.page < 0 || filter.size !in 1..100 || filter.sort !in setOf("code", "name") || filter.direction !in setOf("asc", "desc") ||
            listOfNotNull(filter.search, filter.code, filter.name).any { it.length > 200 }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        return store.list(kind, filter, if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence),
            if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope)
    }

    @Transactional
    fun lookup(value: String): IdentityLookupSnapshot {
        val current = authority.lockCurrent(); permission(current, "inventory.item.view")
        if (value.isBlank() || value.length > 128) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val serial = SerialIdentity.parse(value).canonical
        val mac = try { MacIdentity.parse(value).canonical.replace(":", "") } catch (_: com.duluin.ftth.common.domain.error.ValidationException) { null }
        return store.lookup(serial, mac, if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence),
            if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope,
            current.platformAdmin || "inventory.provenance.view" in current.permissions)
    }

    private fun permission(current: CurrentAuthority, permission: String) {
        if (!current.platformAdmin && permission !in current.permissions) masterFailure(WarehouseErrorCode.FORBIDDEN)
    }

    private fun validate(input: MasterInput, action: MasterAction) {
        if ((action == MasterAction.CREATE) != (input.expectedRevision == null) || (input.expectedRevision ?: 0) < 0) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        fun text(code: String, name: String) {
            if (!code.matches(Regex("[A-Z0-9][A-Z0-9._-]{0,63}")) || name.isBlank() || name.length > 200 || name != name.trim()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        }
        fun optional(value: String?, max: Int) { if (value != null && (value.isBlank() || value.length > max)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
        when (input) {
            is SkuInput -> {
                text(input.code, input.name); optional(input.category, 100); optional(input.model, 200)
                StockUnitDefinition.of(StockTracking.valueOf(input.tracking.name), StockUnit.valueOf(input.baseUnit.name))
                StockQuantity.parseBase(input.minimumQuantityBase, StockUnit.valueOf(input.baseUnit.name))
                if (input.allowedOwnershipModes.isEmpty()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            }
            is SupplierInput -> { text(input.code, input.name); optional(input.contactReference, 500) }
            is LocationInput -> text(input.code, input.name)
            is ArchiveMasterInput -> Unit
        }
    }

    private fun authorizeLocation(location: LocationSnapshot, current: CurrentAuthority, scope: AuthorityScope) {
        if (current.platformAdmin) return
        if (scope is AuthorityScope.Restricted && location.id !in scope.ids) masterFailure(WarehouseErrorCode.NOT_FOUND)
        area(location.areaId, current)
    }

    private fun area(id: UUID?, current: CurrentAuthority) {
        val scope = current.areaScope
        if (!current.platformAdmin && scope is AuthorityScope.Restricted && id !in scope.ids) masterFailure(WarehouseErrorCode.NOT_FOUND)
    }

    private fun validateLocation(id: UUID, input: LocationInput, current: CurrentAuthority, scope: AuthorityScope, creating: Boolean) {
        area(input.areaId, current)
        if (input.areaId != null && iam.areasByIds(setOf(input.areaId)).isEmpty()) masterFailure(WarehouseErrorCode.NOT_FOUND)
        if (input.siteId != null) {
            val site = network.findSite(input.siteId) ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            area(site.areaId, current)
            if (site.areaId != input.areaId) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        }
        if (input.custodianId != null && iam.findUser(input.custodianId)?.active != true) masterFailure(WarehouseErrorCode.NOT_FOUND)
        if (input.kind == LocationKind.TECHNICIAN && input.custodianId == null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if (input.issueEligible && input.kind !in setOf(LocationKind.WAREHOUSE, LocationKind.BIN, LocationKind.TECHNICIAN, LocationKind.VEHICLE)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if (input.kind == LocationKind.BIN && input.parentLocationId == null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val visited = mutableSetOf(id)
        var parent = input.parentLocationId
        while (parent != null) {
            if (!visited.add(parent) || visited.size > 32) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Hierarki lokasi bersiklus atau terlalu dalam")
            val location = store.get(MasterKind.LOCATION, parent, true) as LocationSnapshot
            authorizeLocation(location, current, scope)
            if (location.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            if (location.kind !in setOf(LocationKind.WAREHOUSE, LocationKind.BIN) || location.areaId != input.areaId) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            parent = location.parentLocationId
        }
        if (creating && input.parentLocationId == null && !current.platformAdmin && current.areaScope is AuthorityScope.Restricted && input.areaId == null) masterFailure(WarehouseErrorCode.FORBIDDEN)
    }
}
