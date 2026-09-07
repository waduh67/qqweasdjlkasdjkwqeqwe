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
        if (cutover.snapshot.state != WarehouseCutoverState.ENFORCED) fail(WarehouseErrorCode.CUTOVER_REQUIRED)
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
        require(if (availability) command.legs.isEmpty() && command.reservations.isNotEmpty() else command.legs.isNotEmpty())
        require(command.splits.map { it.parentId }.distinct().size == command.splits.size)
        require(command.reservations.map { it.id }.distinct().size == command.reservations.size)
        require(command.facts.map { it.stockIdentityId }.distinct().size == command.facts.size)
        command.legs.forEach {
            require(it.quantity.quantityBase > 0)
            require(it.status !in setOf(InventoryStatus.RESERVED,InventoryStatus.RECEIPT_SOURCE))
            when (it.endpoint) {
                PostingEndpoint.RECEIPT_SOURCE -> require(command.kind == MovementKind.RECEIVE && it.direction == LegDirection.OUT)
                PostingEndpoint.CONSUMED -> require(command.kind == MovementKind.CONSUME && it.direction == LegDirection.IN && it.status == InventoryStatus.CONSUMED)
                PostingEndpoint.PHYSICAL -> require(it.status != InventoryStatus.CONSUMED)
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
        if(command.kind == MovementKind.CONSUME) {
            require(command.legs.any { it.endpoint == PostingEndpoint.CONSUMED })
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
