package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.InventoryFulfillmentCommand
import com.duluin.ftth.inventory.InventoryFulfillmentResult
import com.duluin.ftth.inventory.WarehouseOperationClass
import com.duluin.ftth.inventory.application.port.outbound.LegacyWarehousePostingPort
import com.duluin.ftth.inventory.application.port.outbound.WarehousePosting
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DurableInventoryFulfillmentService(
    private val posting: WarehousePosting,
    private val policies: InventoryTenantPolicyService,
    private val legacy: LegacyWarehousePostingPort,
) {
    @Transactional(rollbackFor = [Exception::class])
    fun consumeMovement(command: MovementCommand): InventoryMovement {
        require(command.tenantId==TenantContext.tenantId())
        val fence=policies.lockForCommand(policies.read().epoch,WarehouseOperationClass.ORDINARY_STOCK)
        val post=legacy.resolve(legacy.fulfillment(command),false,fence.snapshot.epoch)
        val result=posting.post(post,fence)
        return InventoryMovement(result.postingId,command.tenantId,command.namespace,command.operationKey,command.payloadHash,command.actorId,
            command.reason,result.recordedAt,MovementKind.CONSUME,post.legs.map { leg ->
                MovementLeg(leg.direction,leg.dimension.stockIdentityId,leg.dimension.skuId,leg.dimension.locationId,Math.toIntExact(leg.quantity.quantityBase),true,
                    leg.dimension.custodianId,leg.dimension.custodianKind,leg.status)
            },MovementState.APPLIED)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun apply(command: InventoryFulfillmentCommand, returned: Boolean): InventoryFulfillmentResult {
        require(command.tenantId==TenantContext.tenantId())
        val fence=policies.lockForCommand(policies.read().epoch,WarehouseOperationClass.ORDINARY_STOCK)
        val result=posting.post(legacy.resolve(command,returned,fence.snapshot.epoch),fence)
        return InventoryFulfillmentResult(command.tenantId,command.operationKey,command.targetId,true,false,result.recordedAt)
    }
}
