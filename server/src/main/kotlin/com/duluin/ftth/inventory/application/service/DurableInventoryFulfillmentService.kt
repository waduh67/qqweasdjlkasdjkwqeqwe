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
    private val commands: WarehouseCommandService,
    private val legacy: LegacyWarehousePostingPort,
) {
    @Transactional(rollbackFor = [Exception::class])
    fun consumeMovement(command: MovementCommand): InventoryMovement {
        require(command.tenantId==TenantContext.tenantId())
        return commands.executeMovement(command)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun apply(command: InventoryFulfillmentCommand, returned: Boolean): InventoryFulfillmentResult {
        require(command.tenantId==TenantContext.tenantId())
        val result=commands.executeLegacy(command,returned)
        return InventoryFulfillmentResult(command.tenantId,command.operationKey,command.targetId,true,false,result.serverReceivedAt)
    }
}
