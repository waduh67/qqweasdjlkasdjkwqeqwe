package com.duluin.ftth.hotspot

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.hotspot.application.port.outbound.VoucherRepository
import com.duluin.ftth.hotspot.application.service.VoucherExpiryRunner
import com.duluin.ftth.hotspot.domain.model.Voucher
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class VoucherExpiryRunnerTest {
    private val tenantId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-29T12:00:00Z")

    @Test
    fun `expires active vouchers due now, revokes and disconnects them`() = TenantContext.runAs(tenantId) {
        val due = voucher("DUE-001", now.minusSeconds(1))
        val future = voucher("FUTURE-001", now.plusSeconds(1))
        val repository = InMemoryVoucherRepository(listOf(due, future))
        val actions = mutableListOf<String>()

        assertThat(runner(repository, bngApi(actions)).expire(now)).isEqualTo(1)

        assertThat(due.status).isEqualTo(VoucherStatus.EXPIRED)
        assertThat(future.status).isEqualTo(VoucherStatus.ACTIVE)
        assertThat(actions).containsExactly("revoke:${due.id}", "disconnect:${due.id}")
    }

    @Test
    fun `expiry retry has no duplicate BNG calls`() = TenantContext.runAs(tenantId) {
        val due = voucher("DUE-001", now.minusSeconds(1))
        val repository = InMemoryVoucherRepository(listOf(due))
        val actions = mutableListOf<String>()
        val runner = runner(repository, bngApi(actions))

        runner.expire(now)
        runner.expire(now)

        assertThat(due.status).isEqualTo(VoucherStatus.EXPIRED)
        assertThat(actions).containsExactly("revoke:${due.id}", "disconnect:${due.id}")
    }

    @Test
    fun `unreachable BNG leaves voucher expired`() = TenantContext.runAs(tenantId) {
        val due = voucher("DUE-001", now.minusSeconds(1))
        val repository = InMemoryVoucherRepository(listOf(due))
        val unreachableBng = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BngApi::class.java)) { _, method, _ ->
            if (method.name in setOf("revokeVoucherCredential", "disconnectVoucherCredential")) {
                throw IllegalStateException("NAS unavailable")
            }
            throw UnsupportedOperationException("Unexpected ${method.name} call")
        } as BngApi

        assertThat(runner(repository, unreachableBng).expire(now)).isEqualTo(1)
        assertThat(due.status).isEqualTo(VoucherStatus.EXPIRED)
    }

    private fun runner(repository: VoucherRepository, bngApi: BngApi) =
        VoucherExpiryRunner(repository, bngApi, Clock.fixed(now, ZoneOffset.UTC))

    private fun voucher(username: String, expiresAt: Instant) = Voucher.rehydrate(
        UUID.randomUUID(), tenantId, null, username, UUID.randomUUID(), UUID.randomUUID(), Duration.ofHours(1),
        VoucherStatus.ACTIVE, now.minus(Duration.ofHours(1)), expiresAt, "device-1", null, null, null,
    )

    @Suppress("UNCHECKED_CAST")
    private fun bngApi(actions: MutableList<String>): BngApi = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(BngApi::class.java),
    ) { _, method, args ->
        when (method.name) {
            "revokeVoucherCredential" -> actions += "revoke:${args!![0]}"
            "disconnectVoucherCredential" -> actions += "disconnect:${args!![0]}"
            else -> throw UnsupportedOperationException("Unexpected ${method.name} call")
        }
        null
    } as BngApi

    private class InMemoryVoucherRepository(vouchers: List<Voucher>) : VoucherRepository {
        private val values = vouchers.associateByTo(mutableMapOf()) { it.id }

        override fun save(voucher: Voucher, password: String?): Voucher = voucher.also { values[it.id] = it }
        override fun findById(id: UUID): Voucher? = values[id]
        override fun claim(id: UUID, deviceId: String, clock: Clock): com.duluin.ftth.hotspot.application.port.outbound.VoucherClaim? =
            values[id]?.let { voucher ->
                val activated = voucher.status == VoucherStatus.AVAILABLE
                voucher.claim(deviceId, clock)
                com.duluin.ftth.hotspot.application.port.outbound.VoucherClaim(voucher, activated)
            }
        override fun expireIfDue(id: UUID, now: Instant): Voucher? = values[id]?.takeIf { it.expireIfDue(now) }
        override fun findActiveExpired(now: Instant, limit: Int): List<Voucher> = values.values
            .filter { it.status == VoucherStatus.ACTIVE && it.expiresAt?.let { expiry -> !now.isBefore(expiry) } == true }
            .take(limit)
        override fun search(batchId: UUID?, siteId: UUID?, status: VoucherStatus?, page: PageRequest): Page<Voucher> =
            Page(values.values.toList(), page.page, page.size, values.size.toLong())
    }
}
