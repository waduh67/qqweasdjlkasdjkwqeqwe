package com.duluin.ftth.provisioning.adapter.outbound.bng

import com.duluin.ftth.bng.BngProvisioningApi
import com.duluin.ftth.provisioning.application.port.outbound.SubscriberAccessIsolationPort
import com.duluin.ftth.provisioning.application.port.outbound.SubscriberAccessLifecyclePort
import com.duluin.ftth.provisioning.application.service.SubscriberSessionEvidence
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class BngSubscriberAccessAdapter(
    private val bng: BngProvisioningApi,
) : SubscriberAccessIsolationPort, SubscriberAccessLifecyclePort {
    override fun observe(subscriptionId: UUID): SubscriberSessionEvidence = bng.findAccess(subscriptionId)
        ?.let { SubscriberSessionEvidence(it.activeSessionCount, it.observedAt) }
        ?: SubscriberSessionEvidence(0, Instant.now())

    override fun status(subscriptionId: UUID): String? = bng.findAccess(subscriptionId)?.accountStatus
    override fun activate(subscriptionId: UUID) = bng.activate(subscriptionId)
    override fun isolate(subscriptionId: UUID) = bng.isolate(subscriptionId)
    override fun terminate(subscriptionId: UUID) = bng.terminate(subscriptionId)

    override fun disconnectActiveSessions(subscriptionId: UUID): SubscriberSessionEvidence =
        bng.disconnect(subscriptionId)?.let { SubscriberSessionEvidence(it.activeSessionCount, it.observedAt) }
            ?: SubscriberSessionEvidence(0, Instant.now())
}
