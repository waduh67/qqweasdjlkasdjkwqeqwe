package com.duluin.ftth.hotspot.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.VoucherCredentialSpec
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import com.duluin.ftth.hotspot.application.port.inbound.CreateVoucherBatchCommand
import com.duluin.ftth.hotspot.application.port.inbound.CreateVoucherCommand
import com.duluin.ftth.hotspot.application.port.inbound.GenerateVoucherBatchCommand
import com.duluin.ftth.hotspot.application.port.inbound.GeneratedVoucherBatch
import com.duluin.ftth.hotspot.application.port.inbound.HotspotSessionView
import com.duluin.ftth.hotspot.application.port.inbound.IssuedVoucherCredential
import com.duluin.ftth.hotspot.application.port.inbound.ManageVoucherUseCase
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.application.port.outbound.VoucherBatchRepository
import com.duluin.ftth.hotspot.application.port.outbound.VoucherRepository
import com.duluin.ftth.hotspot.domain.model.Voucher
import com.duluin.ftth.hotspot.domain.model.VoucherBatch
import com.duluin.ftth.hotspot.domain.model.VoucherBatchStatus
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
class VoucherService(
    private val vouchers: VoucherRepository,
    private val batches: VoucherBatchRepository,
    private val hotspotSites: HotspotSiteRepository,
    private val catalogApi: CatalogApi,
    private val bngApi: BngApi,
    private val credentialGenerator: VoucherCredentialGenerator,
    private val clock: Clock,
    private val auditor: AuditRecorder? = null,
    private val meters: MeterRegistry? = null,
) : ManageVoucherUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun createBatch(command: CreateVoucherBatchCommand): VoucherBatch {
        validateIssuance(command.siteId, command.planId)
        return batches.save(VoucherBatch.create(TenantContext.tenantId(), command.siteId, command.planId, command.duration)).also { batch ->
            auditor?.record(
                action = "HOTSPOT_VOUCHER_BATCH_CREATED",
                entityType = "HotspotVoucherBatch",
                entityId = batch.id,
                tenantId = batch.tenantId,
                detail = mapOf("siteId" to batch.siteId, "planId" to batch.planId, "durationMinutes" to batch.duration.toMinutes()),
            )
        }
    }

    override fun create(command: CreateVoucherCommand): Voucher {
        validateIssuance(command.siteId, command.planId)
        command.batchId?.let { batchId ->
            val batch = batches.findById(batchId) ?: throw NotFoundException("Batch voucher tidak ditemukan")
            if (batch.status != VoucherBatchStatus.OPEN) throw ConflictException("Batch voucher sudah ditutup")
            if (batch.siteId != command.siteId || batch.planId != command.planId || batch.duration != command.duration) {
                throw ConflictException("Voucher harus mengikuti site, plan, dan durasi batch")
            }
        }
        return vouchers.save(Voucher.create(TenantContext.tenantId(), command.batchId, command.username, command.password,
            command.siteId, command.planId, command.duration), command.password)
    }

    override fun generateBatch(command: GenerateVoucherBatchCommand): GeneratedVoucherBatch {
        require(command.quantity in 1..1_000) { "Jumlah voucher harus 1..1000" }
        validateIssuance(command.siteId, command.planId)
        val batch = batches.save(VoucherBatch.create(TenantContext.tenantId(), command.siteId, command.planId, command.duration))
        val usernames = HashSet<String>(command.quantity)
        val site = hotspotSites.findById(command.siteId) ?: throw NotFoundException("Site hotspot tidak ditemukan")
        val credentials = (1..command.quantity).map {
            val username = generateUniqueUsername(usernames)
            val password = credentialGenerator.password()
            val voucher = vouchers.save(Voucher.create(TenantContext.tenantId(), batch.id, username, password,
                command.siteId, command.planId, command.duration), password)
            provisionIssuedVoucher(voucher, password, site.nasId)
            IssuedVoucherCredential(voucher, password)
        }
        auditor?.record(
            action = "HOTSPOT_VOUCHER_BATCH_GENERATED",
            entityType = "HotspotVoucherBatch",
            entityId = batch.id,
            tenantId = batch.tenantId,
            detail = mapOf("siteId" to batch.siteId, "planId" to batch.planId, "quantity" to command.quantity),
        )
        return GeneratedVoucherBatch(batch, credentials)
    }

    @Transactional(readOnly = true)
    override fun listBatches(siteId: UUID?, page: PageRequest): Page<VoucherBatch> = batches.search(siteId, page)

    @Transactional(readOnly = true)
    override fun getBatch(batchId: UUID): VoucherBatch = batches.findById(batchId) ?: throw NotFoundException("Batch voucher tidak ditemukan")

    @Transactional(readOnly = true)
    override fun listVouchers(batchId: UUID?, siteId: UUID?, status: VoucherStatus?, page: PageRequest): Page<Voucher> =
        vouchers.search(batchId, siteId, status, page)

    @Transactional(readOnly = true)
    override fun getVoucher(voucherId: UUID): Voucher = vouchers.findById(voucherId) ?: throw NotFoundException("Voucher tidak ditemukan")

    @Transactional(readOnly = true)
    override fun getSession(voucherId: UUID): HotspotSessionView {
        val voucher = getVoucher(voucherId)
        val session = bngApi.findVoucherSession(voucher.id.toString())
        return HotspotSessionView(
            voucherId = voucher.id,
            online = session?.online ?: false,
            nasId = session?.nasId,
            ipAddress = session?.framedIp,
            sessionId = session?.sessionId,
            deviceId = session?.callingStationId ?: voucher.deviceId,
            startedAt = session?.startedAt,
            lastSeenAt = session?.lastSeenAt,
            inputBytes = session?.inputBytes,
            outputBytes = session?.outputBytes,
        )
    }

    override fun claim(voucherId: UUID, deviceId: String): Voucher = try {
        val claim = vouchers.claim(voucherId, deviceId, clock) ?: throw NotFoundException("Voucher tidak ditemukan")
        val voucher = claim.voucher
        if (claim.activated) {
            auditor?.record(
                action = "HOTSPOT_VOUCHER_REDEEMED",
                entityType = "HotspotVoucher",
                entityId = voucher.id,
                tenantId = voucher.tenantId,
                detail = mapOf("siteId" to voucher.siteId, "status" to voucher.status.name),
            )
        }
        voucher
    } catch (exception: RuntimeException) {
        meters?.counter("ftth.hotspot.redemption.failures")?.increment()
        log.warn("hotspot_redemption_failed voucherId={}", voucherId, exception)
        throw exception
    }

    override fun revoke(voucherId: UUID, reason: String): Voucher {
        val voucher = vouchers.findById(voucherId) ?: throw NotFoundException("Voucher tidak ditemukan")
        val wasRevoked = voucher.status == VoucherStatus.REVOKED
        voucher.revoke(TenantContext.tenantId(), reason, clock)
        val saved = vouchers.save(voucher)
        if (!wasRevoked) {
            bngApi.revokeVoucherCredential(saved.id.toString())
            bngApi.disconnectVoucherCredential(saved.id.toString())
            auditor?.record(
                action = "HOTSPOT_VOUCHER_REVOKED",
                entityType = "HotspotVoucher",
                entityId = saved.id,
                tenantId = saved.tenantId,
                detail = mapOf("reason" to saved.revocationReason),
            )
        }
        return saved
    }

    private fun provisionIssuedVoucher(voucher: Voucher, password: String, nasId: UUID) {
        try {
            bngApi.provisionVoucherCredential(
                VoucherCredentialSpec(voucher.id.toString(), voucher.username, password, voucher.planId, nasId),
            )
        } catch (exception: RuntimeException) {
            meters?.counter("ftth.hotspot.provisioning.failures")?.increment()
            log.warn("hotspot_voucher_handoff_failed voucherId={} siteId={}", voucher.id, voucher.siteId, exception)
            throw exception
        }
    }

    private fun generateUniqueUsername(issued: MutableSet<String>): String {
        repeat(100) {
            credentialGenerator.username().also { if (issued.add(it)) return it }
        }
        throw ConflictException("Gagal membuat kode voucher unik")
    }

    private fun validateIssuance(siteId: UUID, planId: UUID) {
        val site = hotspotSites.findById(siteId) ?: throw NotFoundException("Hotspot site tidak ditemukan")
        if (site.portalMode == com.duluin.ftth.hotspot.domain.model.PortalMode.OFF) {
            throw ConflictException("Hotspot site sedang dinonaktifkan")
        }
        if (catalogApi.findActiveHotspotPlan(planId) == null) throw NotFoundException("Paket HOTSPOT aktif tidak ditemukan")
    }

}
