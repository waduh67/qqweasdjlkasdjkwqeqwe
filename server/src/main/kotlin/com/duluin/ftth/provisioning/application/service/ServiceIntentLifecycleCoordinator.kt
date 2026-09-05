package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.provisioning.application.port.outbound.ServiceIntentRepository
import com.duluin.ftth.provisioning.application.port.outbound.ServiceSegmentState
import com.duluin.ftth.provisioning.application.port.outbound.ServiceSegmentStatePort
import com.duluin.ftth.provisioning.application.port.outbound.SubscriberAccessLifecyclePort
import com.duluin.ftth.provisioning.application.port.outbound.SubscriptionLifecycleStatusPort
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class ServiceIntentLifecycleCoordinator(
    private val intents: ServiceIntentRepository,
    private val segments: ServiceSegmentStatePort,
    private val access: SubscriberAccessLifecyclePort,
    private val subscriptions: SubscriptionLifecycleStatusPort,
) {
    fun onActivated(subscriptionId: UUID) {
        val intent = intents.findBySubscriptionId(subscriptionId)
        if (intent == null) access.activate(subscriptionId) else intent.activateAccess()
    }

    fun onIsolated(subscriptionId: UUID) {
        if (access.status(subscriptionId) != "ISOLATED") {
            access.isolate(subscriptionId)
        }
    }

    fun onTerminated(subscriptionId: UUID) {
        val intent = intents.findBySubscriptionId(subscriptionId)
        if (intent == null) access.terminate(subscriptionId) else intent.terminateAccess()
    }

    fun reconcile() {
        intents.findAll().forEach { intent ->
            val subscriptionId = intent.subscriptionId ?: return@forEach
            when (subscriptions.statusOf(subscriptionId)) {
                "ACTIVE" -> intent.activateAccess()
                "ISOLATED" -> if (access.status(subscriptionId) != "ISOLATED") access.isolate(subscriptionId)
                "TERMINATED" -> intent.terminateAccess()
                else -> Unit
            }
        }
    }

    private fun ServiceIntent.activateAccess() {
        val subscriptionId = subscriptionId ?: return
        if (segments.stateOf(id) == ServiceSegmentState.APPLIED && access.status(subscriptionId) != "ACTIVE") {
            access.activate(subscriptionId)
        }
    }

    private fun ServiceIntent.terminateAccess() {
        val subscriptionId = subscriptionId ?: return
        if (segments.stateOf(id) == ServiceSegmentState.REMOVED && access.status(subscriptionId) != "TERMINATED") {
            access.terminate(subscriptionId)
        }
    }
}
