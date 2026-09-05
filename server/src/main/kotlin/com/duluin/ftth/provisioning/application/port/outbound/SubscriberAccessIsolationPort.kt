package com.duluin.ftth.provisioning.application.port.outbound

import com.duluin.ftth.provisioning.application.service.SubscriberSessionEvidence
import java.util.UUID

interface SubscriberAccessIsolationPort {
    fun observe(subscriptionId: UUID): SubscriberSessionEvidence
    fun isolate(subscriptionId: UUID)
    fun disconnectActiveSessions(subscriptionId: UUID): SubscriberSessionEvidence
    fun terminate(subscriptionId: UUID)
}

interface SubscriberAccessLifecyclePort {
    fun status(subscriptionId: UUID): String?
    fun activate(subscriptionId: UUID)
    fun isolate(subscriptionId: UUID)
    fun terminate(subscriptionId: UUID)
}

enum class ServiceSegmentState { PENDING, APPLIED, REMOVED }

interface ServiceSegmentStatePort {
    fun stateOf(intentId: UUID): ServiceSegmentState
}

interface SubscriptionLifecycleStatusPort {
    fun statusOf(subscriptionId: UUID): String?
}
