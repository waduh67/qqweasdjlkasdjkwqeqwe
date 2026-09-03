package com.duluin.ftth.provisioning.application.port.outbound

import com.duluin.ftth.provisioning.application.service.SubscriberSessionEvidence
import java.util.UUID

interface SubscriberAccessIsolationPort {
    fun observe(subscriptionId: UUID): SubscriberSessionEvidence
    fun isolate(subscriptionId: UUID)
    fun disconnectActiveSessions(subscriptionId: UUID): SubscriberSessionEvidence
}
