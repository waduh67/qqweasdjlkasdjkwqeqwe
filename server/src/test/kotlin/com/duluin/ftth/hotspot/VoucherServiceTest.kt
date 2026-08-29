package com.duluin.ftth.hotspot

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.VoucherActionRef
import com.duluin.ftth.bng.VoucherCredentialRef
import com.duluin.ftth.bng.VoucherCredentialSpec
import com.duluin.ftth.bng.VoucherSessionRef
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.context.ApplicationEventPublisher
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.hotspot.application.port.inbound.CreateVoucherCommand
import com.duluin.ftth.hotspot.application.port.inbound.GenerateVoucherBatchCommand
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.application.port.outbound.VoucherBatchRepository
import com.duluin.ftth.hotspot.application.port.outbound.VoucherRepository
import com.duluin.ftth.hotspot.application.service.VoucherCredentialGenerator
import com.duluin.ftth.hotspot.application.service.VoucherService
import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.Voucher
import com.duluin.ftth.hotspot.domain.model.VoucherBatch
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class VoucherServiceTest {
    private val tenantId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-29T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val siteId = UUID.randomUUID()
    private val planId = UUID.randomUUID()

    @Test
    fun `redemption audits without credential secrets and records provisioning failures`() = asTenant {
        val events = mutableListOf<Any>()
        val meters = SimpleMeterRegistry()
        val failingBng = object : RecordingBngApi() {
            override fun provisionVoucherCredential(command: VoucherCredentialSpec): VoucherCredentialRef =
                throw IllegalStateException("BNG unavailable")
        }
        val service = service(
            InMemoryVoucherRepository(),
            failingBng,
            AuditRecorder(ApplicationEventPublisher { events += it }, object : CurrentUserProvider {
                override fun currentOrNull() = null
            }),
            meters,
        )
        val voucher = service.create(command())

        service.claim(voucher.id, "device-a")

        assertThat(meters.find("ftth.hotspot.provisioning.failures").counter()).isNull()
        assertThat(meters.find("ftth.hotspot.redemption.failures").counter()).isNull()
        assertThat(events).hasSize(1)
    }

    @Test
    fun `first claim activates once and same client retry is idempotent`() = asTenant {
        val repository = InMemoryVoucherRepository()
        val voucher = service(repository).create(command())

        val activated = service(repository).claim(voucher.id, "device-a")
        val retry = service(repository).claim(voucher.id, "device-a")

        assertThat(activated.status).isEqualTo(VoucherStatus.ACTIVE)
        assertThat(activated.activatedAt).isEqualTo(now)
        assertThat(activated.expiresAt).isEqualTo(now.plus(Duration.ofHours(1)))
        assertThat(retry.activatedAt).isEqualTo(activated.activatedAt)
        assertThat(retry.expiresAt).isEqualTo(activated.expiresAt)
    }

    @Test
    fun `claim provisions exactly one opaque credential through BNG and retry is idempotent`() = asTenant {
        val vouchers = InMemoryVoucherRepository()
        val bng = RecordingBngApi()
        val voucher = service(vouchers, bng).create(command())

        service(vouchers, bng).claim(voucher.id, "device-a")
        service(vouchers, bng).claim(voucher.id, "device-a")

        assertThat(bng.provisions).isEmpty()
    }

    @Test
    fun `provisioning retry does not duplicate the activation action`() = asTenant {
        val vouchers = InMemoryVoucherRepository()
        val bng = object : RecordingBngApi() {
            var attempts = 0
            override fun provisionVoucherCredential(command: VoucherCredentialSpec): VoucherCredentialRef {
                attempts += 1
                if (attempts == 1) throw IllegalStateException("BNG unavailable")
                return super.provisionVoucherCredential(command)
            }
        }
        val voucher = service(vouchers, bng).create(command())

        val retry = service(vouchers, bng).claim(voucher.id, "device-a")

        assertThat(retry.status).isEqualTo(VoucherStatus.ACTIVE)
        assertThat(bng.attempts).isZero()
        assertThat(bng.provisions).isEmpty()
    }

    @Test
    fun `claim at expiry is rejected without a provisioning action`() = asTenant {
        val vouchers = InMemoryVoucherRepository()
        val bng = RecordingBngApi()
        val voucher = service(vouchers, bng).create(command())
        service(vouchers, bng).claim(voucher.id, "device-a")
        voucher.expiresAt = now

        assertThatThrownBy { service(vouchers, bng).claim(voucher.id, "device-a") }
            .isInstanceOf(ConflictException::class.java)
        assertThat(bng.provisions).isEmpty()
    }

    @Test
    fun `revoke deprovisions and disconnects voucher exactly once`() = asTenant {
        val vouchers = InMemoryVoucherRepository()
        val bng = RecordingBngApi()
        val voucher = service(vouchers, bng).create(command())
        service(vouchers, bng).claim(voucher.id, "device-a")

        service(vouchers, bng).revoke(voucher.id, "fraud")
        service(vouchers, bng).revoke(voucher.id, "fraud")

        assertThat(bng.revocations).containsExactly(voucher.id.toString())
        assertThat(bng.disconnects).containsExactly(voucher.id.toString())
    }

    @Test
    fun `different client cannot claim an active voucher`() = asTenant {
        val repository = InMemoryVoucherRepository()
        val voucher = service(repository).create(command())
        service(repository).claim(voucher.id, "device-a")

        assertThatThrownBy { service(repository).claim(voucher.id, "device-b") }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `expired and revoked vouchers reject claim`() = asTenant {
        val expired = Voucher.create(tenantId, null, "expiry-code", "password", siteId, planId, Duration.ofMinutes(1))
        expired.claim("device-a", Clock.fixed(now.minus(Duration.ofMinutes(2)), ZoneOffset.UTC))
        val repository = InMemoryVoucherRepository().also { it.save(expired) }

        assertThatThrownBy { service(repository).claim(expired.id, "device-a") }.isInstanceOf(ConflictException::class.java)

        val revoked = service(repository).create(command("revoked-code"))
        service(repository).revoke(revoked.id, "fraud")
        assertThatThrownBy { service(repository).claim(revoked.id, "device-a") }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `session projection maps safe BNG fields and preserves bound device`() = asTenant {
        val vouchers = InMemoryVoucherRepository()
        val bng = RecordingBngApi()
        val voucher = service(vouchers, bng).create(command())
        service(vouchers, bng).claim(voucher.id, "bound-device")
        bng.session = VoucherSessionRef(
            externalId = voucher.id.toString(),
            online = true,
            nasId = bng.nasId,
            framedIp = "10.1.2.3",
            sessionId = "session-1",
            callingStationId = null,
            startedAt = now.minusSeconds(60),
            lastSeenAt = now,
            inputBytes = 123L,
            outputBytes = 456L,
        )

        val session = service(vouchers, bng).getSession(voucher.id)

        assertThat(session).isEqualTo(
            com.duluin.ftth.hotspot.application.port.inbound.HotspotSessionView(
                voucher.id, true, bng.nasId, "10.1.2.3", "session-1", "bound-device",
                now.minusSeconds(60), now, 123L, 456L,
            ),
        )
    }

    @Test
    fun `voucher from another tenant is not passed to BNG`() = asTenant {
        val vouchers = InMemoryVoucherRepository()
        val bng = RecordingBngApi()

        assertThatThrownBy { service(vouchers, bng).getSession(UUID.randomUUID()) }
            .isInstanceOf(com.duluin.ftth.common.domain.error.NotFoundException::class.java)
        assertThat(bng.sessionLookups).isEmpty()
    }

    @Test
    fun `missing BNG observation yields an offline safe session view`() = asTenant {
        val vouchers = InMemoryVoucherRepository()
        val voucher = service(vouchers).create(command())

        val session = service(vouchers).getSession(voucher.id)

        assertThat(session.online).isFalse()
        assertThat(session.voucherId).isEqualTo(voucher.id)
        assertThat(session.nasId).isNull()
        assertThat(session.ipAddress).isNull()
        assertThat(session.inputBytes).isNull()
        assertThat(session.outputBytes).isNull()
    }

    @Test
    fun `password is write only and omitted from returned voucher`() = asTenant {
        val voucher = service(InMemoryVoucherRepository()).create(command())

        assertThat(Voucher::class.java.declaredFields.map { it.name }).doesNotContain("password", "secret")
        assertThat(voucher.username).isEqualTo("VOUCHER-CODE")
    }

    @Test
    fun `generates one hundred unique secure credentials for an eligible site and plan`() = asTenant {
        val generated = service(InMemoryVoucherRepository()).generateBatch(
            GenerateVoucherBatchCommand(siteId, planId, Duration.ofHours(1), 100),
        )

        assertThat(generated.credentials).hasSize(100)
        assertThat(generated.credentials.map { it.voucher.username }).doesNotHaveDuplicates()
        assertThat(generated.credentials.map { it.password }).doesNotHaveDuplicates()
        assertThat(generated.credentials).allSatisfy {
            assertThat(it.voucher.username).matches("VCH-[A-Z0-9]{10}")
            assertThat(it.password).hasSize(12)
        }
    }

    private fun command(username: String = "Voucher-Code") =
        CreateVoucherCommand(null, username, "a-secret-password", siteId, planId, Duration.ofHours(1))

    private fun service(
        vouchers: VoucherRepository,
        bng: BngApi = RecordingBngApi(),
        auditor: AuditRecorder? = null,
        meters: SimpleMeterRegistry? = null,
    ) = VoucherService(
        vouchers,
        InMemoryBatchRepository(),
        object : HotspotSiteRepository {
            override fun save(site: HotspotSite): HotspotSite = site
            override fun findById(id: UUID): HotspotSite? =
                if (id == siteId) HotspotSite.create(tenantId, UUID.randomUUID(), "Test site", null, com.duluin.ftth.hotspot.domain.model.PortalMode.NAS_OWNED) else null
            override fun findByNasId(nasId: UUID): HotspotSite? = null
            override fun findByPortalId(portalId: String): HotspotSite? = null
            override fun findAll(): List<HotspotSite> = emptyList()
        },
        object : CatalogApi {
            override fun findPlanCommercial(planId: UUID) = null
            override fun findPlanByName(name: String) = null
            override fun findPlanNetwork(planId: UUID) = null
            override fun findActiveHotspotPlan(planId: UUID): PlanNetworkRef? =
                if (planId == this@VoucherServiceTest.planId) PlanNetworkRef(planId, "Hotspot", 10, 10, "10M/10M", null, false, null, null, null, null, setOf("HOTSPOT")) else null
            override fun findActivePlans() = emptyList<com.duluin.ftth.catalog.PlanCommercialRef>()
        },
        bng,
        VoucherCredentialGenerator(),
        clock,
        auditor,
        meters,
    )
    private fun asTenant(block: () -> Unit) = TenantContext.runAs(tenantId, block)

    private class InMemoryVoucherRepository : VoucherRepository {
        private val values = mutableMapOf<UUID, Voucher>()
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
            .filter { it.status == VoucherStatus.ACTIVE && it.expiresAt?.let { expiresAt -> !now.isBefore(expiresAt) } == true }
            .take(limit)
        override fun search(batchId: UUID?, siteId: UUID?, status: VoucherStatus?, page: PageRequest): Page<Voucher> =
            Page(values.values.toList(), page.page, page.size, values.size.toLong())
    }
    private open class RecordingBngApi : BngApi {
        val nasId = UUID.randomUUID()
        val provisions = mutableListOf<VoucherCredentialSpec>()
        val revocations = mutableListOf<String>()
        val disconnects = mutableListOf<String>()
        var session: VoucherSessionRef? = null
        val sessionLookups = mutableListOf<String>()

        override fun findVoucherSession(externalId: String): VoucherSessionRef? {
            sessionLookups += externalId
            return session?.takeIf { it.externalId == externalId }
        }

        override fun provisionVoucherCredential(command: VoucherCredentialSpec): VoucherCredentialRef {
            if (provisions.none { it.externalId == command.externalId }) provisions += command
            return VoucherCredentialRef(command.externalId, command.username, "PENDING", null, null)
        }

        override fun revokeVoucherCredential(externalId: String): VoucherCredentialRef? {
            if (externalId !in revocations) revocations += externalId
            return null
        }

        override fun disconnectVoucherCredential(externalId: String): VoucherActionRef? {
            if (externalId !in disconnects) disconnects += externalId
            return null
        }

        override fun findSubscriberSession(customerId: UUID) = null
        override fun findPppoeByCustomerIds(customerIds: Set<UUID>) = emptyMap<UUID, com.duluin.ftth.bng.SubscriberPppoeRef>()
        override fun provisionAccess(command: com.duluin.ftth.bng.ProvisionAccessSpec) = throw UnsupportedOperationException()
        override fun resolveNasForArea(areaId: UUID) = null
        override fun resolveNasByName(name: String) = null
        override fun findAccessByUsername(username: String) = null
        override fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?) = Unit
        override fun fetchPppSecretsFromNas(nasId: UUID) = emptyList<com.duluin.ftth.bng.PppSecretRef>()
        override fun activeSubscriberLiveness() = emptyList<com.duluin.ftth.bng.SubscriberPppoeLiveness>()
        override fun exportAccesses() = emptyList<com.duluin.ftth.bng.AccessExportRef>()
    }

    private class InMemoryBatchRepository : VoucherBatchRepository {
        override fun save(batch: VoucherBatch): VoucherBatch = batch
        override fun findById(id: UUID): VoucherBatch? = null
        override fun search(siteId: UUID?, page: PageRequest): Page<VoucherBatch> = Page(emptyList(), page.page, page.size, 0)
    }
}
