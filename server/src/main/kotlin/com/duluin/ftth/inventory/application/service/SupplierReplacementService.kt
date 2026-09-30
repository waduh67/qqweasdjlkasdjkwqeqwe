package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class SupplierReplacementService(private val cutovers: InventoryTenantCutoverApi, private val authorities: CurrentAuthorityApi,
    private val returns: WarehouseReturnStore, private val replacements: SupplierReplacementStore,
    private val receipts: WarehouseReceiptService, private val receiptStore: WarehouseReceiptPersistence,
    private val workOrders: AssetHandoverWorkOrderPort, private val masters: WarehouseMasterStore,
    private val access: WarehousePolicyAccess, private val clock: WarehousePolicyPersistence,
    private val scopes: InventoryWarehouseScopeApi, private val sites: com.duluin.ftth.network.SiteReferenceApi) : InventorySupplierReplacementApi {
    private val mapper = jacksonObjectMapper()

    override fun request(id: UUID, input: SupplierReplacementInput, metadata: WarehouseMutationMetadata): SupplierReplacementView {
        receiptKey(metadata.idempotencyKey)
        if (input.expectedRevision !in 0 until Long.MAX_VALUE ||
            listOf(input.externalReference, input.evidenceReference).any { it.isBlank() || it.length > 500 })
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val serial = SerialIdentity.parse(input.serial).canonical
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authorities.lockCurrent()
        listOf("inventory.return.manage", "inventory.receipt.manage").forEach { access.permission(current, it) }
        var context = replacements.context(id)
        val workRevision = workOrders.lockTitle(context.workOrderId, current.fence)
        masters.lockTopology()
        val returned = returns.get(id, true)
        listOf(returned.intake.quarantineLocationId, returned.view.locationId, input.sourceLocationId, input.inspectionLocationId)
            .distinct().sortedBy(UUID::toString).forEach { access.location(it, current) }
        context = context.copy(assetRevision = replacements.lockAsset(returned.view.stockIdentityId))
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "input" to input)))
        replacements.replay(metadata.idempotencyKey, canonical.hash, current.fence.identity.userId)?.let {
            if (it.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            return it.view
        }
        val view = returned.view
        if (view.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val repair = view.repair ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (view.origin != WarehouseReturnOrigin.ASSET_REMOVAL || view.state != WarehouseReturnState.REPAIR || repair.returnedRevision != null ||
            view.legalOwner !in setOf(AssetLegalOwner.ISP, AssetLegalOwner.CUSTOMER) || input.skuId != view.skuId ||
            returned.source.serial?.let { SerialIdentity.parse(it).canonical } == serial) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val position = returns.position(returned.source.dimension.copy(locationId = view.locationId, custodianId = repair.vendorId,
            custodianKind = OwnerKind.REPAIR, condition = view.condition, legalOwner = view.legalOwner))
        if (position.tracking != WarehouseTracking.SERIAL || position.quantity != 1L) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val requestId = UUID.randomUUID()
        val draft = receipts.replacementDraft(ReceiptDraftInput(repair.vendorId, input.externalReference, input.sourceLocationId,
            input.inspectionLocationId, listOf(ReceiptLineInput(input.skuId, "1", listOf(ReceiptSerialInput(input.serial, input.mac)),
                cost = input.cost?.let { ReceiptCostInput(it.totalMinor, it.currency) }))),
            "replacement-receipt:$requestId", ReceiptDraftContext(context.customerId, context.workOrderId, workRevision, view.legalOwner))
        val receipt = receiptStore.get(draft.documentId)
        val reference = SupplierReplacementView(requestId, id, repair.id, receipt.id, view.stockIdentityId, view.legalOwner)
        replacements.insert(SupplierReplacementRecord(reference, returned, context, position, receipt, input, current.fence.identity.userId,
            workRevision, current.fence.epoch, cutover.snapshot.epoch, clock.now()), metadata.idempotencyKey, canonical)
        return reference
    }

    override fun list(id: UUID, page: WarehousePageRequest): List<SupplierReplacementView> {
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authorities.lockCurrent()
        listOf("inventory.return.view", "inventory.receipt.view").forEach { access.permission(current, it) }
        workOrders.lockTitle(replacements.context(id).workOrderId, current.fence)
        masters.lockTopology()
        val returned = returns.get(id)
        listOf(returned.intake.quarantineLocationId, returned.view.locationId).distinct().forEach { access.location(it, current) }
        val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
        val visibility = WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false)
        return replacements.list(id, page, visibility)
    }
}
