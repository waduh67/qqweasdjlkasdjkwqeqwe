package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class WarehousePostingService(
    private val store: WarehousePostingStore,
    private val policies: InventoryTenantPolicyService,
) : WarehousePosting {
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
    override fun post(command: WarehousePost, cutover: TenantCutoverFence): WarehousePostResult {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        cutover.assertHeld()
        check(cutover.snapshot.tenantId == TenantContext.tenantId())
        val opening = command.kind == MovementKind.OPENING_BALANCE && command.approval?.kind == ApprovalPostingKind.OPENING_BALANCE &&
            command.operation.namespace == "warehouse.approval.effect"
        if (cutover.snapshot.state != WarehouseCutoverState.ENFORCED && !(opening && cutover.snapshot.state == WarehouseCutoverState.VALIDATING))
            fail(WarehouseErrorCode.CUTOVER_REQUIRED)
        if (opening && cutover.snapshot.state != WarehouseCutoverState.VALIDATING) fail(WarehouseErrorCode.CUTOVER_REQUIRED)
        validate(command)
        return store.write(command, cutover.snapshot.epoch)
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
    override fun rebuild(expectedCutoverEpoch: Long): List<PostingBalance> {
        policies.lockForTransition(expectedCutoverEpoch).assertHeld()
        return store.rebuild()
    }

    private fun validate(command: WarehousePost) {
        require(command.expectedRevision >= 0 && command.expectedRevision < Long.MAX_VALUE)
        require(command.reason.isNotBlank())
        val availability = command.kind in setOf(MovementKind.RESERVE, MovementKind.RELEASE)
        val opening = command.kind == MovementKind.OPENING_BALANCE
        if (opening) require(command.approval?.kind == ApprovalPostingKind.OPENING_BALANCE && command.operation.namespace == "warehouse.approval.effect" &&
            command.reservations.isEmpty() && command.splits.isEmpty() && command.facts.isEmpty() && command.usage == null && command.compensatesPostingId == null)
        require(if (availability) command.legs.isEmpty() && (command.reservations.isNotEmpty() ||
            command.events.any { it.kind == WarehouseEventKind.RESERVED }) else opening || command.legs.isNotEmpty())
        require(command.splits.map { it.parentId }.distinct().size == command.splits.size)
        require(command.reservations.map { it.id }.distinct().size == command.reservations.size)
        require(command.facts.map { it.stockIdentityId }.distinct().size == command.facts.size)
        command.legs.forEach {
            require(it.quantity.quantityBase > 0)
            require(it.status !in setOf(InventoryStatus.RESERVED,InventoryStatus.RECEIPT_SOURCE))
            when (it.endpoint) {
                PostingEndpoint.RECEIPT_SOURCE -> require(command.kind in setOf(MovementKind.RECEIVE, MovementKind.OPENING_BALANCE) && it.direction == LegDirection.OUT)
                PostingEndpoint.CONSUMED -> require(command.kind == MovementKind.CONSUME && it.direction == LegDirection.IN && it.status == InventoryStatus.CONSUMED)
                PostingEndpoint.CUSTOMER_INSTALLED -> require((command.kind == MovementKind.DEPLOY && it.direction == LegDirection.IN &&
                    command.operation.namespace == "warehouse.deployment.consume" || command.kind == MovementKind.TITLE_TRANSFER &&
                    command.operation.namespace == "warehouse.asset.handover" || command.kind == MovementKind.TITLE_CORRECTION &&
                    command.operation.namespace == "warehouse.approval.effect" && command.approval?.kind == ApprovalPostingKind.TITLE_CORRECTION ||
                    command.kind == MovementKind.RETURN && command.operation.namespace == "warehouse.asset.remove" && it.direction == LegDirection.OUT ||
                    command.kind == MovementKind.LOSS && command.operation.namespace == "warehouse.approval.effect" &&
                    command.approval?.kind == ApprovalPostingKind.ASSET_LOSS && it.direction == LegDirection.OUT) &&
                    it.status == InventoryStatus.CUSTOMER_INSTALLED && it.dimension.custodianKind == OwnerKind.CUSTOMER &&
                    it.quantity == StockQuantity.of(1, StockUnit.EA))
                PostingEndpoint.PHYSICAL -> require(it.status !in setOf(InventoryStatus.CONSUMED, InventoryStatus.CUSTOMER_INSTALLED))
            }
        }
        command.legs.groupBy { Triple(it.dimension.skuId,it.dimension.lotId,it.quantity.unit) }.values.forEach { legs ->
            val zero = StockQuantity.of(0,legs.first().quantity.unit)
            val inbound = legs.filter { it.direction == LegDirection.IN }.fold(zero) { sum, leg -> sum + leg.quantity }
            val outbound = legs.filter { it.direction == LegDirection.OUT }.fold(zero) { sum, leg -> sum + leg.quantity }
            require(inbound == outbound) { "Physical posting must conserve base quantities per SKU, lot and unit" }
        }
        val parentForChild = command.splits.flatMap { split -> split.children.map { it.id to split.parentId } }.toMap()
        command.legs.groupBy { parentForChild[it.dimension.stockIdentityId] ?: it.dimension.stockIdentityId }.values.forEach { legs ->
            val zero = StockQuantity.of(0,legs.first().quantity.unit)
            require(legs.filter { it.direction == LegDirection.IN }.fold(zero) { sum, leg -> sum + leg.quantity } ==
                legs.filter { it.direction == LegDirection.OUT }.fold(zero) { sum, leg -> sum + leg.quantity }) { "Unrelated stock identities cannot offset each other" }
        }
        if(command.kind in setOf(MovementKind.CONSUME, MovementKind.DEPLOY)) {
            require(command.legs.any { it.endpoint in setOf(PostingEndpoint.CONSUMED, PostingEndpoint.CUSTOMER_INSTALLED) })
            require(command.legs.filter { it.direction==LegDirection.OUT }.all { it.status==InventoryStatus.ISSUED && it.dimension.custodianKind==OwnerKind.TECHNICIAN })
            require(command.legs.filter { it.endpoint==PostingEndpoint.CONSUMED }.all { leg -> command.facts.any { it.stockIdentityId==leg.dimension.stockIdentityId && it.installed } })
        }
        command.facts.forEach { fact ->
            require(fact.useRevision > 0 && fact.installed != fact.returned)
            val leg = command.legs.single { it.direction == LegDirection.IN && it.dimension.stockIdentityId == fact.stockIdentityId }
            require(leg.quantity == fact.quantity)
            require(if(fact.installed) leg.endpoint == PostingEndpoint.CONSUMED else command.kind == MovementKind.RETURN)
        }
    }

    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code,code.name))
}
