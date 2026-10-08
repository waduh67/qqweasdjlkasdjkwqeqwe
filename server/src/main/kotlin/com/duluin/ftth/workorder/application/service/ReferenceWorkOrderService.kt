package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceRequestStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.inventory.application.service.referenceTimestamp
import com.duluin.ftth.workorder.WorkOrderAssigned
import com.duluin.ftth.workorder.adapter.outbound.persistence.ReferenceWorkOrderStore
import com.duluin.ftth.workorder.application.port.inbound.*
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class ReferenceWorkOrderService(private val store: ReferenceWorkOrderStore, private val workOrders: WorkOrderRepository,
    private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi, private val owners: TenantOwnerStore,
    private val iam: IamApi, private val customers: CustomerApi, private val settings: ReferenceRequestStore,
    private val entityManager: EntityManager, private val events: ApplicationEventPublisher) {
    private val mapper = jacksonObjectMapper()
    internal data class Access(val current: CurrentAuthority, val cutover: TenantCutoverFence)

    fun types(): List<ReferenceWorkOrderTypeView> {
        val access = readAccess()
        readPermission(access.current)
        store.lockTypes()
        store.ensureDefaults()
        return store.types().filterNot { it.deleted }
    }
    fun saveType(id: UUID?, input: ReferenceWorkOrderTypeInput, key: String): WarehouseOperationReceipt {
        val access = access(key)
        owner(access.current)
        val canonical = canonical(id, input)
        replay("TYPE_SAVE", key, canonical, access)?.let { return it }
        text(input.name, 200, true)
        if (input.name != input.name.trim() || input.photoSlots.size !in 1..12 || input.photoSlots.distinct().size != input.photoSlots.size ||
            input.photoSlots.map { it.lowercase() }.distinct().size != input.photoSlots.size) malformed()
        input.photoSlots.forEach { text(it, 100, true); if (it != it.trim()) malformed() }
        store.lockTypes()
        store.ensureDefaults()
        val prior = id?.let { store.type(it) }
        if (prior?.deleted == true || input.expectedRevision != (prior?.revision ?: 0)) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (store.types().any { it.id != id && it.name.equals(input.name, true) }) malformed("Nama jenis sudah digunakan")
        val view = ReferenceWorkOrderTypeView(id ?: UUID.randomUUID(), prior?.revision?.plus(1) ?: 0, input.name,
            input.workType, input.materialRequired, input.photoSlots, input.active)
        store.saveType(view, prior == null)
        return record("TYPE_SAVE", key, canonical, access, view.id, "TYPE", view.revision, view, "Jenis work order", "OWNER", if (prior == null) 201 else 200)
    }
    fun deleteType(id: UUID, revision: Long, key: String): WarehouseOperationReceipt {
        val access = access(key)
        owner(access.current)
        val canonical = canonical(id, mapOf("expectedRevision" to revision))
        replay("TYPE_DELETE", key, canonical, access)?.let { return it }
        store.lockTypes()
        val prior = store.type(id)
        if (prior.revision != revision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (prior.deleted || store.typeUsed(id)) malformed("Jenis yang dipakai work order tidak bisa dihapus")
        val view = prior.copy(revision = prior.revision + 1, active = false, deleted = true)
        store.saveType(view, false)
        return record("TYPE_DELETE", key, canonical, access, id, "TYPE", view.revision, view, "Jenis dihapus", "OWNER")
    }
    fun create(input: ReferenceWorkOrderInput, key: String): WarehouseOperationReceipt {
        val access = access(key)
        permission(access.current, "workorder.order.create")
        val canonical = canonical(null, input)
        replay("CREATE", key, canonical, access)?.let { return it }
        validateDetails(input.title, input.description, input.areaId, input.customerId, null, access)
        val technician = technician(input.technicianId)
        store.lockTypes()
        store.ensureDefaults()
        val type = store.type(input.typeId).takeIf { it.active && !it.deleted } ?: malformed("Pilih jenis aktif")
        input.subscriptionId?.let { id -> if (customers.findSubscription(id)?.customerId != input.customerId || input.customerId == null) malformed("Langganan harus milik pelanggan") }
        val now = referenceTimestamp()
        val scheduledAt = input.scheduledAt?.let(::referenceTimestamp)
        val order = WorkOrder.open(access.current.fence.identity.tenantId, type.workType, input.title, input.description, input.priority,
            input.customerId, null, input.areaId, scheduledAt, setOf(technician.id), access.current.fence.identity.userId,
            input.subscriptionId, input.orderId, now)
        workOrders.save(order)
        entityManager.flush()
        val view = ReferenceWorkOrderView(order.id, order.code, 0, type, input.title.trim(), input.description, input.priority,
            input.customerId, technician.id, technician.name, input.areaId, scheduledAt, ReferenceWorkOrderState.PENDING, 0, now, null, now)
        store.save(view, true)
        assigned(view)
        return record("CREATE", key, canonical, access, view.id, "WO", 0, view, "Work order dibuat", "workorder.order.create", 201)
    }
    fun update(id: UUID, input: ReferenceWorkOrderUpdate, key: String): WarehouseOperationReceipt {
        val access = access(key)
        permission(access.current, "workorder.order.update")
        val canonical = canonical(id, input)
        replay("UPDATE", key, canonical, access)?.let { return it }
        val prior = editable(id, input.expectedRevision, access)
        validateDetails(input.title, input.description, input.areaId, input.customerId, prior.customerId, access)
        val order = workOrders.findById(id) ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        if (order.subscriptionId != null && prior.customerId != input.customerId) malformed("Pelanggan terikat langganan tidak bisa diganti")
        val scheduledAt = input.scheduledAt?.let(::referenceTimestamp)
        order.updateDetails(input.title, input.description, input.priority, input.customerId, null, input.areaId, scheduledAt,
            referenceTimestamp(), access.current.fence.identity.userId)
        workOrders.save(order)
        entityManager.flush()
        val view = prior.copy(revision = prior.revision + 1, title = input.title.trim(), description = input.description, priority = input.priority,
            customerId = input.customerId, areaId = input.areaId, scheduledAt = scheduledAt)
        store.save(view, false)
        return record("UPDATE", key, canonical, access, id, "WO", view.revision, view, "Rincian diperbarui", "workorder.order.update")
    }
    fun assign(id: UUID, input: ReferenceWorkOrderAssignment, key: String): WarehouseOperationReceipt {
        val access = access(key)
        permission(access.current, "workorder.order.assign")
        val canonical = canonical(id, input)
        replay("ASSIGN", key, canonical, access)?.let { return it }
        val prior = editable(id, input.expectedRevision, access)
        val technician = technician(input.technicianId)
        if (prior.technicianId == technician.id) malformed("Pilih teknisi pengganti")
        val now = referenceTimestamp()
        val order = workOrders.findById(id) ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        order.reassignReference(technician.id, now, access.current.fence.identity.userId)
        workOrders.save(order)
        entityManager.flush()
        val view = prior.copy(revision = prior.revision + 1, technicianId = technician.id, technicianName = technician.name,
            assignmentGeneration = prior.assignmentGeneration + 1, state = ReferenceWorkOrderState.PENDING, blockedReason = null, lastActivityAt = now)
        store.save(view, false)
        assigned(view)
        return record("ASSIGN", key, canonical, access, id, "WO", view.revision, view,
            "${prior.technicianName} diganti ke ${technician.name}", "workorder.order.assign")
    }
    fun progress(id: UUID, input: ReferenceWorkOrderProgress, key: String): WarehouseOperationReceipt {
        val access = access(key)
        permission(access.current, "workorder.order.field")
        val canonical = canonical(id, input)
        replay("PROGRESS", key, canonical, access)?.let { return it }
        val prior = editable(id, input.expectedRevision, access)
        field(prior, access)
        if (input.state !in setOf(ReferenceWorkOrderState.PENDING, ReferenceWorkOrderState.BLOCKED)) malformed()
        text(input.notes, 1000, input.state == ReferenceWorkOrderState.BLOCKED)
        val now = referenceTimestamp()
        val order = workOrders.findById(id) ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        order.noteFieldActivity(if (input.state == ReferenceWorkOrderState.BLOCKED) "Kendala: ${input.notes}" else input.notes, now, access.current.fence.identity.userId)
        workOrders.save(order)
        entityManager.flush()
        val view = prior.copy(revision = prior.revision + 1, state = input.state, lastActivityAt = now,
            blockedReason = if (input.state == ReferenceWorkOrderState.BLOCKED) input.notes else null)
        store.save(view, false)
        return record("PROGRESS", key, canonical, access, id, "WO", view.revision, view, input.notes, "workorder.order.field")
    }
    fun detail(id: UUID): ReferenceWorkOrderDetail {
        val access = readAccess()
        val view = authorized(store.get(id), access)
        val deadline = view.lastActivityAt.plusSeconds(settings.settings().overdueDays.toLong() * 86400)
        return ReferenceWorkOrderDetail(view, view.state == ReferenceWorkOrderState.PENDING && !Instant.now().isBefore(deadline), deadline, store.timeline(id))
    }
    fun list(page: Int, size: Int, state: ReferenceWorkOrderState?, search: String?, overdue: Boolean): WarehousePage<ReferenceWorkOrderView> {
        if (page < 0 || size !in 1..100 || search != null && search.length > 200) malformed()
        val access = readAccess()
        readPermission(access.current)
        return store.list(page, size, access.current.areaScope, if (permits(access.current, "workorder.order.view")) null else access.current.fence.identity.userId,
            state, search, overdue, settings.settings().overdueDays)
    }
    internal fun access(key: String): Access {
        if (key.length !in 1..240 || key.any { it.code !in 33..126 }) malformed()
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.REFERENCE_WORK_ORDER)
        return Access(authority.lockCurrent(), cutover)
    }
    internal fun readAccess(): Access {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        return Access(authority.lockCurrent(), cutover)
    }
    internal fun canonical(id: UUID?, input: Any) = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "input" to input)))
    internal fun authorized(view: ReferenceWorkOrderView, access: Access): ReferenceWorkOrderView {
        readPermission(access.current)
        area(view.areaId, access.current)
        if (!permits(access.current, "workorder.order.view") && view.technicianId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.NOT_FOUND)
        return view
    }
    internal fun editable(id: UUID, revision: Long, access: Access): ReferenceWorkOrderView = authorized(store.get(id, true), access).also {
        if (it.revision != revision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (it.state in setOf(ReferenceWorkOrderState.COMPLETED, ReferenceWorkOrderState.CANCELLED)) malformed("Work order sudah ditutup")
    }
    internal fun field(view: ReferenceWorkOrderView, access: Access) {
        permission(access.current, "workorder.order.field")
        if (view.technicianId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        technician(view.technicianId)
    }
    internal fun replay(action: String, key: String, canonical: WarehouseCanonicalPayload, access: Access): WarehouseOperationReceipt? {
        val prior = store.lockKey(action, key) ?: return null
        if (prior.permission == "OWNER") owner(access.current) else permission(access.current, prior.permission)
        if (prior.actorId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.epoch != access.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        if (prior.resourceKind == "WO") {
            val view = authorized(store.get(prior.resourceId), access)
            if (prior.permission == "workorder.order.field") field(view, access)
        }
        return prior.receipt
    }
    internal fun record(action: String, key: String, canonical: WarehouseCanonicalPayload, access: Access, id: UUID, kind: String, revision: Long,
        view: Any, notes: String, permission: String, status: Int = 200) = store.command(action, key, id, kind, revision,
        access.current.fence.identity.userId, access.current.fence.epoch, access.cutover.snapshot.epoch, canonical, view, notes, permission, status)
    internal fun text(value: String, max: Int, required: Boolean = false) {
        if (value.length > max || required && value.isBlank() || value.any { it.isISOControl() && it != '\n' } ||
            value.contains('<') || value.contains('>')) malformed("Gunakan teks biasa tanpa HTML")
    }
    private fun validateDetails(title: String, description: String, areaId: UUID, customerId: UUID?, previousCustomer: UUID?, access: Access) {
        text(title, 200, true); text(description, 2000)
        area(areaId, access.current)
        if (iam.areasByIds(setOf(areaId)).isEmpty()) masterFailure(WarehouseErrorCode.NOT_FOUND)
        customerId?.let {
            val customer = customers.findCustomer(it) ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            if (it != previousCustomer && customer.status !in setOf("PROSPECT", "ACTIVE")) malformed("Pilih pelanggan aktif")
        }
    }
    private fun area(id: UUID, current: CurrentAuthority) {
        if (!current.platformAdmin && current.areaScope is AuthorityScope.Restricted && id !in current.areaScope.ids) masterFailure(WarehouseErrorCode.NOT_FOUND)
    }
    private fun technician(id: UUID) = iam.findUser(id)?.takeIf { it.active && it.pureTechnician && owners.findUserId() != id }
        ?: malformed("Pilih satu teknisi NE atau FO aktif")
    private fun readPermission(current: CurrentAuthority) {
        if (!permits(current, "workorder.order.view") && !permits(current, "workorder.order.field")) masterFailure(WarehouseErrorCode.FORBIDDEN)
    }
    private fun permits(current: CurrentAuthority, code: String) = current.platformAdmin || code in current.permissions
    private fun permission(current: CurrentAuthority, code: String) { if (!permits(current, code)) masterFailure(WarehouseErrorCode.FORBIDDEN) }
    private fun owner(current: CurrentAuthority) { if (owners.findUserId() != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN) }
    private fun malformed(message: String = "Data work order tidak valid"): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, message)
    private fun assigned(view: ReferenceWorkOrderView) {
        events.publishEvent(WorkOrderAssigned(cutovers.read().tenantId, view.id, view.code, view.title, listOf(view.technicianId), view.scheduledAt, view.customerId))
    }
}
