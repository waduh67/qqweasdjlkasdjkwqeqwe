package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

data class DisconnectConfirmation(
    val activeSessionCount: Int,
    val observedAt: Instant,
)

fun interface SubscriberSessionDisconnectConfirmer {
    fun confirm(usernames: Set<String>, initialActiveSessionCount: Int): DisconnectConfirmation
}

@Component
class RadiusSubscriberSessionDisconnectConfirmer(
    private val worker: RadiusSessionControlRunner,
    private val radius: RadiusAccountingReadPort,
    private val tenants: TenantApi,
    @Value("\${ftth.bng.disconnect-confirm-timeout:PT5S}") private val timeout: Duration,
    @Value("\${ftth.bng.disconnect-confirm-interval:PT0.1S}") private val interval: Duration,
) : SubscriberSessionDisconnectConfirmer, DisposableBean {
    init {
        require(!timeout.isZero && !timeout.isNegative) { "DISCONNECT_CONFIRM_TIMEOUT_INVALID" }
        require(!interval.isZero && !interval.isNegative) { "DISCONNECT_CONFIRM_INTERVAL_INVALID" }
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread.ofPlatform().daemon().name("radius-disconnect-confirm").unstarted(task)
    }

    override fun confirm(usernames: Set<String>, initialActiveSessionCount: Int): DisconnectConfirmation {
        require(initialActiveSessionCount > 0) { "ACTIVE_SESSION_REQUIRED" }
        require(usernames.isNotEmpty()) { "SESSION_USERNAME_REQUIRED" }
        val tenantId = TenantContext.tenantId()
        val tenantCode = tenants.findById(tenantId)?.slug
            ?: return DisconnectConfirmation(initialActiveSessionCount, Instant.now())
        if (!radius.isConfigured()) return DisconnectConfirmation(initialActiveSessionCount, Instant.now())
        if (runCatching { worker.run(tenantId) }.isFailure) {
            return DisconnectConfirmation(initialActiveSessionCount, Instant.now())
        }

        val lastEvidence = AtomicReference(DisconnectConfirmation(initialActiveSessionCount, Instant.now()))
        val confirmed = CompletableFuture<DisconnectConfirmation>()
        val polling = scheduler.scheduleWithFixedDelay(
            {
                runCatching {
                    TenantContext.runAs(tenantId) {
                        val observedAt = Instant.now()
                        val count = radius.activeSessions(tenantId, tenantCode)
                            .count { it.online && it.username in usernames }
                        DisconnectConfirmation(count, observedAt).also {
                            lastEvidence.set(it)
                            if (count == 0) confirmed.complete(it)
                        }
                    }
                }
            },
            0,
            interval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        return confirmed.awaitOrLast(timeout, polling, lastEvidence)
    }

    override fun destroy() {
        scheduler.shutdownNow()
    }
}

private fun CompletableFuture<DisconnectConfirmation>.awaitOrLast(
    timeout: Duration,
    polling: ScheduledFuture<*>,
    lastEvidence: AtomicReference<DisconnectConfirmation>,
): DisconnectConfirmation = try {
    get(timeout.toMillis(), TimeUnit.MILLISECONDS)
} catch (_: TimeoutException) {
    lastEvidence.get()
} catch (_: ExecutionException) {
    lastEvidence.get()
} catch (_: CancellationException) {
    lastEvidence.get()
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    lastEvidence.get()
} finally {
    polling.cancel(false)
}
