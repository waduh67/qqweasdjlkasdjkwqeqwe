package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.BngNasRef
import com.duluin.ftth.bng.BngProvisioningApi
import com.duluin.ftth.bng.BngSubscriberAccessRef
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.NasVendor
import com.duluin.ftth.catalog.CatalogApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
@Transactional(readOnly = true)
class BngProvisioningApiService(
    private val accesses: SubscriberAccessRepository,
    private val sessions: RadiusSessionRepository,
    private val nas: NasRepository,
    private val catalog: CatalogApi,
    private val lifecycle: SubscriberAccessLifecycle,
    private val actions: BngActionService,
    private val disconnectConfirmer: SubscriberSessionDisconnectConfirmer,
    @Value("\${ftth.bng.session-stale-after:PT3M}") private val sessionStaleAfter: Duration,
) : BngProvisioningApi {
    override fun findNas(nasId: UUID): BngNasRef? = nas.findById(nasId)?.let {
        BngNasRef(it.id, it.enabled, it.enabled && it.vendor != NasVendor.OTHER)
    }

    override fun findAccess(subscriptionId: UUID): BngSubscriberAccessRef? {
        val access = accesses.findBySubscriptionId(subscriptionId).singleOrNull() ?: return null
        val now = Instant.now()
        val activeSessions = sessions.findBySubscriberAccessId(access.id)
            ?.takeIf { it.isLiveAt(now, sessionStaleAfter) }
            ?.let { 1 } ?: 0
        val plan = catalog.findPlanNetwork(access.planId)
        val capable = access.authType == AuthType.PPPOE &&
            access.nasId?.let(::findNas)?.pppoeTerminationCapable == true &&
            plan?.serviceTypes?.contains(AuthType.PPPOE.name) == true
        return BngSubscriberAccessRef(
            access.id, subscriptionId, access.nasId, access.planId, plan?.name, access.authType.name,
            access.status.name, capable, activeSessions, now,
        )
    }

    @Transactional
    override fun activate(subscriptionId: UUID) = lifecycle.onActivated(subscriptionId)

    @Transactional
    override fun isolate(subscriptionId: UUID) = lifecycle.onIsolated(subscriptionId)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun disconnect(subscriptionId: UUID): BngSubscriberAccessRef? {
        val owned = accesses.findBySubscriptionId(subscriptionId)
            .filter { it.status != AccessStatus.TERMINATED }
        val before = findAccess(subscriptionId) ?: return null
        if (before.activeSessionCount == 0) return before
        if (!owned.all { actions.enqueueDisconnect(it, null, null) }) return before
        val confirmation = disconnectConfirmer.confirm(owned.mapTo(linkedSetOf()) { it.username }, before.activeSessionCount)
        return before.copy(
            activeSessionCount = confirmation.activeSessionCount,
            observedAt = confirmation.observedAt,
        )
    }

    @Transactional
    override fun terminate(subscriptionId: UUID) = lifecycle.onTerminated(subscriptionId)
}
