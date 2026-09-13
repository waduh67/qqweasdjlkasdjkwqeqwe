package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.*
import com.duluin.ftth.customer.adapter.outbound.persistence.CustomerFulfillmentReceiptStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class CustomerFulfillmentService(private val customer: CustomerApi, private val receipts: CustomerFulfillmentReceiptStore,
    private val operationScope: com.duluin.ftth.common.infrastructure.persistence.FulfillmentOperationScope) : CustomerFulfillmentApi {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun apply(command: CustomerFulfillmentCommand, authority: AuthorityFence) {
        authority.assertHeld()
        check(authority.identity.tenantId == TenantContext.tenantId())
        if (receipts.replay(command)) return
        val before = receipts.state(command)
        operationScope.enter(command.reference)
        when (command.action) {
            CustomerFulfillmentAction.ACTIVATE -> customer.activateForInstallation(command.subscriptionId)
            CustomerFulfillmentAction.TERMINATE -> customer.terminateForDismantle(command.subscriptionId)
        }
        receipts.record(command, authority.identity.userId, before)
    }
}
