package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.common.security.ReadOnlyLockGuard
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementasi [ReadOnlyLockGuard]: tenant baca-saja bila langganan SaaS-nya SUSPENDED —
 * satu-satunya cara status itu muncul adalah tagihan yang menunggak melewati masa tenggang
 * (lihat `PlatformBillingRunner.enforce`).
 *
 * Jawabannya di-CACHE 60 detik karena pemanggilnya adalah cek izin di setiap request yang
 * menulis: tanpa cache, setiap klik menambah satu query ke tabel langganan hanya untuk
 * mendapat jawaban "tidak" yang sama sepanjang hari. TTL-nya pendek, dan yang membuat
 * pelunasan terasa seketika tetap [invalidate] — TTL cuma jaring pengaman bila ada jalur yang
 * lupa memanggilnya.
 */
@Component
class SubscriptionLockGuard(
    private val subscriptions: TenantSubscriptionRepository,
    private val tenants: TenantApi,
) : ReadOnlyLockGuard {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<UUID, Cached>()

    @Transactional(readOnly = true)
    override fun isReadOnly(): Boolean {
        val tenantId = TenantContext.tenantIdOrNull() ?: return false
        // Tenant "platform" adalah rumah para admin platform; mengunci mereka berarti mengunci
        // orang yang justru harus bisa menagih dan membuka kembali tenant lain.
        if (tenantId == tenants.platformTenantId()) return false

        cache[tenantId]?.takeIf { it.isFresh() }?.let { return it.locked }
        // Kegagalan baca (tabel belum termigrasi, koneksi putus sesaat) TIDAK boleh mengunci
        // siapa pun: kunci ini menutup seluruh konsol, jadi ketidakpastian dijawab "terbuka".
        val locked = runCatching { subscriptions.findByTenantId(tenantId)?.status == SubscriptionStatus.SUSPENDED }
            .onFailure { log.warn("Status langganan tenant {} tak terbaca; konsol dibiarkan terbuka", tenantId, it) }
            .getOrDefault(false)
        cache[tenantId] = Cached(locked, Instant.now().plus(TTL))
        return locked
    }

    override fun invalidate(tenantId: UUID) {
        cache.remove(tenantId)
    }

    private data class Cached(val locked: Boolean, val expiresAt: Instant) {
        fun isFresh(): Boolean = Instant.now().isBefore(expiresAt)
    }

    private companion object {
        val TTL: Duration = Duration.ofSeconds(60)
    }
}
