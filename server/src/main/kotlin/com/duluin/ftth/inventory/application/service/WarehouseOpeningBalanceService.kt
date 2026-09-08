package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.OpeningBalanceInput
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class WarehouseOpeningBalanceService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi) {
    @Transactional(rollbackFor = [Exception::class])
    fun request(input: OpeningBalanceInput, key: String, contentType: String, bytes: ByteArray): Nothing {
        receiptKey(key)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.provenance.manage")
        receiptText(input.migrationReference, 500)
        receiptText(input.sourceSnapshot, 10000)
        if (input.cutoff > Instant.now()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        validateReceiptEvidence(contentType, bytes)
        masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
    }
}
