package com.karuhun.ftth.provisioning.application.service

import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.provisioning.application.port.outbound.ServiceIntentRepository
import com.karuhun.ftth.provisioning.application.port.outbound.SubscriberAccessLifecyclePort
import com.karuhun.ftth.provisioning.domain.model.ServiceIntent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProvisioningIntentLifecycleService(
    private val intents: ServiceIntentRepository,
    private val access: SubscriberAccessLifecyclePort,
) {
    @Transactional
    fun suspend(id: UUID): ServiceIntent {
        val intent = intent(id)
        val subscriptionId = intent.subscriptionId ?: throw NotFoundException("FIXED_SUBSCRIPTION_REQUIRED")
        access.isolate(subscriptionId)
        intent.suspend()
        return intents.save(intent)
    }

    @Transactional
    fun restore(id: UUID): ServiceIntent {
        val intent = intent(id)
        val subscriptionId = intent.subscriptionId ?: throw NotFoundException("FIXED_SUBSCRIPTION_REQUIRED")
        access.activate(subscriptionId)
        intent.activate()
        return intents.save(intent)
    }

    private fun intent(id: UUID) = intents.findById(id) ?: throw NotFoundException("SERVICE_INTENT_NOT_FOUND")
}
