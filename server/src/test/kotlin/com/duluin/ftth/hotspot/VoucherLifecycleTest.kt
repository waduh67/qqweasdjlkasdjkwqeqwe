package com.duluin.ftth.hotspot

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.hotspot.domain.model.Voucher
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VoucherLifecycleTest {
    private val startedAt = Instant.parse("2026-08-29T10:00:00Z")
    private val clock = Clock.fixed(startedAt, ZoneOffset.UTC)

    @Test
    fun `first redemption sets immutable expiry and same device retry is idempotent`() {
        val voucher = voucher()

        voucher.claim("client-a", clock)
        val expiry = voucher.expiresAt
        voucher.claim("client-a", Clock.fixed(startedAt.plus(Duration.ofHours(1)), ZoneOffset.UTC))

        assertThat(voucher.status).isEqualTo(VoucherStatus.ACTIVE)
        assertThat(voucher.activatedAt).isEqualTo(startedAt)
        assertThat(voucher.expiresAt).isEqualTo(expiry)
        assertThat(voucher.expiresAt).isEqualTo(startedAt.plus(Duration.ofMinutes(1440)))
        assertThat(voucher.deviceId).isEqualTo("client-a")
    }

    @Test
    fun `concurrent same-device claims activate once and retain the original expiry`() {
        val voucher = voucher()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) {
                Callable { voucher.claim("client-a", clock) }
            }).map { it.get(1, TimeUnit.SECONDS) }

            assertThat(results).allSatisfy { claimed -> assertThat(claimed).isSameAs(voucher) }
            assertThat(voucher.status).isEqualTo(VoucherStatus.ACTIVE)
            assertThat(voucher.activatedAt).isEqualTo(startedAt)
            assertThat(voucher.expiresAt).isEqualTo(startedAt.plus(Duration.ofMinutes(1440)))
            assertThat(voucher.deviceId).isEqualTo("client-a")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `other client revoked and expired voucher claims conflict`() {
        val voucher = voucher()
        voucher.claim("client-a", clock)

        assertThatThrownBy { voucher.claim("client-b", clock) }.isInstanceOf(ConflictException::class.java)
        voucher.revoke(UUID.randomUUID(), "operator correction", clock)
        assertThatThrownBy { voucher.claim("client-a", clock) }.isInstanceOf(ConflictException::class.java)

        val expired = voucher("EXPIRED-001")
        expired.claim("client-a", clock)
        assertThatThrownBy {
            expired.claim("client-a", Clock.fixed(startedAt.plus(Duration.ofDays(1)), ZoneOffset.UTC))
        }.isInstanceOf(ConflictException::class.java)
        assertThat(expired.status).isEqualTo(VoucherStatus.EXPIRED)
    }

    @Test
    fun `username canonicalization and read model never retain password`() {
        val voucher = voucher(" sale-01 ")
        assertThat(voucher.username).isEqualTo("SALE-01")

        val rehydrated = Voucher.rehydrate(
            voucher.id, voucher.tenantId, voucher.batchId, voucher.username, voucher.siteId, voucher.planId,
            voucher.duration, voucher.status, voucher.activatedAt, voucher.expiresAt, voucher.deviceId,
            voucher.revokedAt, voucher.revokedBy, voucher.revocationReason,
        )
        assertThat(rehydrated.javaClass.declaredFields.map { it.name }).doesNotContain("password")
    }

    private fun voucher(username: String = "DAY-1440"): Voucher = Voucher.create(
        tenantId = UUID.randomUUID(), batchId = null, username = username, password = "random-secret",
        siteId = UUID.randomUUID(), planId = UUID.randomUUID(), duration = Duration.ofMinutes(1440),
    )
}
