package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.*
import com.duluin.ftth.bng.adapter.outbound.persistence.BngFulfillmentReceiptStore
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY)
class BngFulfillmentService(private val owner: BngProvisioningApi, private val receipts: BngFulfillmentReceiptStore,
    private val operationScope: com.duluin.ftth.common.infrastructure.persistence.FulfillmentOperationScope) : BngFulfillmentApi {
    override fun lock(subscriptionId: UUID, customerId: UUID) = receipts.lock(subscriptionId,customerId)

    override fun apply(command: BngFulfillmentCommand, authority: AuthorityFence) {
        authority.assertHeld()
        check(authority.identity.tenantId == TenantContext.tenantId())
        if (receipts.replay(command)) return
        if (receipts.lock(command.binding.subscriptionId,command.binding.customerId) != command.binding) throw ConflictException("FULFILLMENT_BNG_STALE")
        operationScope.enter(command.reference)
        owner.applyFulfillment(command.binding.subscriptionId,command.action)
        receipts.record(command,authority.identity.userId)
    }
}
