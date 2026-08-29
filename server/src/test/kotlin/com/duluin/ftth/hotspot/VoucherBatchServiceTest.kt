package com.duluin.ftth.hotspot

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.VoucherCredentialRef
import com.duluin.ftth.bng.VoucherCredentialSpec
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.hotspot.application.port.inbound.GenerateVoucherBatchCommand
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.application.port.outbound.VoucherBatchRepository
import com.duluin.ftth.hotspot.application.port.outbound.VoucherRepository
import com.duluin.ftth.hotspot.application.service.VoucherCredentialGenerator
import com.duluin.ftth.hotspot.application.service.VoucherService
import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.PortalMode
import com.duluin.ftth.hotspot.domain.model.Voucher
import com.duluin.ftth.hotspot.domain.model.VoucherBatch
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class VoucherBatchServiceTest {
    private val tenantId = UUID.randomUUID()
    private val otherTenantId = UUID.randomUUID()
    private val siteId = UUID.randomUUID()
    private val planId = UUID.randomUUID()
    private val clock = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `generates one hundred unique canonical credentials and passwords are immediate only`() = asTenant {
        val voucherRepository = InMemoryVouchers()
        val result = service(voucherRepository).generateBatch(GenerateVoucherBatchCommand(siteId, planId, Duration.ofHours(1), 100))

        assertThat(result.credentials).hasSize(100)
        assertThat(result.credentials.map { it.voucher.username }).doesNotHaveDuplicates()
        assertThat(result.credentials.map { it.voucher.username }).allMatch { it.matches(Regex("[A-Z0-9][A-Z0-9_-]{2,63}")) }
        assertThat(result.credentials.map { it.password }).allMatch { it.length == 12 }
        assertThat(service(voucherRepository).listVouchers(result.batch.id, null, null, PageRequest(size = 200)).content)
            .allSatisfy { voucher -> assertThat(Voucher::class.java.declaredFields.map { it.name }).doesNotContain("password", "secret") }
    }

    @Test
    fun `generation hands each plaintext credential to BNG once with its stable external ID`() = asTenant {
        val vouchers = InMemoryVouchers()
        val provisions = mutableListOf<VoucherCredentialSpec>()
        val bng = java.lang.reflect.Proxy.newProxyInstance(
            BngApi::class.java.classLoader,
            arrayOf(BngApi::class.java),
        ) { _, method, arguments ->
            if (method.name == "provisionVoucherCredential") {
                val spec = arguments!!.single() as VoucherCredentialSpec
                provisions += spec
                VoucherCredentialRef(spec.externalId, spec.username, "PENDING", null, null)
            } else {
                throw UnsupportedOperationException(method.name)
            }
        } as BngApi
        val result = VoucherService(vouchers, InMemoryBatches(), sites(true), catalog(true), bng, VoucherCredentialGenerator(), clock)
            .generateBatch(GenerateVoucherBatchCommand(siteId, planId, Duration.ofHours(1), 2))

        assertThat(provisions).hasSize(2)
        assertThat(provisions.map { it.externalId }).containsExactlyInAnyOrderElementsOf(result.credentials.map { it.voucher.id.toString() })
        assertThat(provisions.map { it.credential }).containsExactlyInAnyOrderElementsOf(result.credentials.map { it.password })
        assertThat(provisions).allSatisfy { spec -> assertThat(spec.planId).isEqualTo(planId) }
    }

    @Test
    fun `rejects invalid quantity missing site and non hotspot plan`() = asTenant {
        assertThatThrownBy { service().generateBatch(GenerateVoucherBatchCommand(siteId, planId, Duration.ofHours(1), 0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service().generateBatch(GenerateVoucherBatchCommand(siteId, planId, Duration.ofHours(1), 1_001)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service(siteExists = false).generateBatch(GenerateVoucherBatchCommand(siteId, planId, Duration.ofHours(1), 1)) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { service(hotspotPlanExists = false).generateBatch(GenerateVoucherBatchCommand(siteId, planId, Duration.ofHours(1), 1)) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `disabled site rejects new issuance while preserving historic vouchers`() = asTenant {
        val voucherRepository = InMemoryVouchers()
        val existing = Voucher.create(tenantId, null, "EXISTING", "password", siteId, planId, Duration.ofHours(1))
        voucherRepository.save(existing, "password")
        val disabledSite = HotspotSite.create(tenantId, UUID.randomUUID(), "Lobby", null, PortalMode.OFF)
        val application = VoucherService(
            voucherRepository,
            InMemoryBatches(),
            object : HotspotSiteRepository {
                override fun save(site: HotspotSite): HotspotSite = site
                override fun findById(id: UUID): HotspotSite? = disabledSite.takeIf { id == siteId }
                override fun findByNasId(nasId: UUID): HotspotSite? = null
                override fun findByPortalId(portalId: String): HotspotSite? = null
                override fun findAll(): List<HotspotSite> = emptyList()
            },
            catalog(true),
            unsupportedBng(),
            VoucherCredentialGenerator(),
            clock,
        )

        assertThatThrownBy { application.generateBatch(GenerateVoucherBatchCommand(siteId, planId, Duration.ofHours(1), 1)) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(application.getVoucher(existing.id)).isEqualTo(existing)
        assertThat(application.listVouchers(null, siteId, null, PageRequest(size = 20)).content).containsExactly(existing)
    }

    @Test
    fun `tenant scoped repositories hide foreign voucher and revoke is terminal idempotent`() {
        val repository = InMemoryVouchers()
        val batchRepository = InMemoryBatches()
        val application = service(repository, batchRepository)
        val voucherId = TenantContext.runAs(tenantId) {
            application.generateBatch(GenerateVoucherBatchCommand(siteId, planId, Duration.ofHours(1), 1)).credentials.single().voucher.id
        }

        TenantContext.runAs(otherTenantId) {
            assertThat(application.listVouchers(null, null, null, PageRequest()).content).isEmpty()
            assertThatThrownBy { application.getVoucher(voucherId) }.isInstanceOf(NotFoundException::class.java)
        }
        TenantContext.runAs(tenantId) {
            assertThat(application.revoke(voucherId, "operator correction").status).isEqualTo(VoucherStatus.REVOKED)
            assertThat(application.revoke(voucherId, "repeat request").status).isEqualTo(VoucherStatus.REVOKED)
        }
    }

    private fun service(
        vouchers: InMemoryVouchers = InMemoryVouchers(),
        batches: InMemoryBatches = InMemoryBatches(),
        siteExists: Boolean = true,
        hotspotPlanExists: Boolean = true,
    ) = VoucherService(vouchers, batches, sites(siteExists), catalog(hotspotPlanExists), unsupportedBng(), VoucherCredentialGenerator(), clock)

    private fun asTenant(block: () -> Unit) = TenantContext.runAs(tenantId, block)

    private fun unsupportedBng(): BngApi = java.lang.reflect.Proxy.newProxyInstance(
        BngApi::class.java.classLoader,
        arrayOf(BngApi::class.java),
    ) { _, method, _ ->
        if (method.name in setOf("provisionVoucherCredential", "revokeVoucherCredential", "disconnectVoucherCredential")) null else throw UnsupportedOperationException()
    } as BngApi

    private fun sites(exists: Boolean) = sites(
        if (exists) HotspotSite.create(tenantId, UUID.randomUUID(), "Lobby", null, PortalMode.NAS_OWNED) else null,
    )

    private fun sites(site: HotspotSite?) = object : HotspotSiteRepository {
        override fun save(site: HotspotSite): HotspotSite = site
        override fun findAll(): List<HotspotSite> = listOfNotNull(site)
        override fun findById(id: UUID): HotspotSite? = site?.takeIf { id == siteId && TenantContext.tenantId() == tenantId }
        override fun findByNasId(nasId: UUID): HotspotSite? = null
        override fun findByPortalId(portalId: String): HotspotSite? = null
    }

    private fun catalog(exists: Boolean) = object : CatalogApi {
        override fun findPlanCommercial(planId: UUID): PlanCommercialRef? = null
        override fun findPlanByName(name: String): PlanCommercialRef? = null
        override fun findPlanNetwork(planId: UUID): PlanNetworkRef? = null
        override fun findActiveHotspotPlan(planId: UUID): PlanNetworkRef? = if (exists && planId == this@VoucherBatchServiceTest.planId) planNetworkRef() else null
        override fun findActivePlans(): List<PlanCommercialRef> = emptyList()
    }

    private fun planNetworkRef() = PlanNetworkRef(planId, "Hotspot", 10, 10, "10M/10M", null, false, null, null, null, null, setOf("HOTSPOT"))

    private class InMemoryVouchers : VoucherRepository {
        private val values = mutableMapOf<UUID, Voucher>()
        override fun save(voucher: Voucher, password: String?): Voucher = voucher.also { values[it.id] = it }
        override fun findById(id: UUID): Voucher? = values[id]?.takeIf { it.tenantId == TenantContext.tenantId() }
        override fun claim(id: UUID, deviceId: String, clock: Clock): com.duluin.ftth.hotspot.application.port.outbound.VoucherClaim? =
            findById(id)?.let { voucher ->
                val activated = voucher.status == VoucherStatus.AVAILABLE
                voucher.claim(deviceId, clock)
                com.duluin.ftth.hotspot.application.port.outbound.VoucherClaim(voucher, activated)
            }
        override fun expireIfDue(id: UUID, now: java.time.Instant): Voucher? = findById(id)?.takeIf { it.expireIfDue(now) }
        override fun findActiveExpired(now: java.time.Instant, limit: Int): List<Voucher> = values.values
            .filter { it.tenantId == TenantContext.tenantId() && it.status == VoucherStatus.ACTIVE && it.expiresAt?.let { expiresAt -> !now.isBefore(expiresAt) } == true }
            .take(limit)
        override fun search(batchId: UUID?, siteId: UUID?, status: VoucherStatus?, page: PageRequest): Page<Voucher> {
            val all = values.values.filter { it.tenantId == TenantContext.tenantId() && (batchId == null || it.batchId == batchId) && (siteId == null || it.siteId == siteId) && (status == null || it.status == status) }
            return Page(all.drop(page.page * page.size).take(page.size), page.page, page.size, all.size.toLong())
        }
    }

    private class InMemoryBatches : VoucherBatchRepository {
        private val values = mutableMapOf<UUID, VoucherBatch>()
        override fun save(batch: VoucherBatch): VoucherBatch = batch.also { values[it.id] = it }
        override fun findById(id: UUID): VoucherBatch? = values[id]?.takeIf { it.tenantId == TenantContext.tenantId() }
        override fun search(siteId: UUID?, page: PageRequest): Page<VoucherBatch> {
            val all = values.values.filter { it.tenantId == TenantContext.tenantId() && (siteId == null || it.siteId == siteId) }
            return Page(all.drop(page.page * page.size).take(page.size), page.page, page.size, all.size.toLong())
        }
    }
}
