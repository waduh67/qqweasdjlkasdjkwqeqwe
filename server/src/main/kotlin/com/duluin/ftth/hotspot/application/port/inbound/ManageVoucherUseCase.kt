package com.duluin.ftth.hotspot.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.hotspot.domain.model.Voucher
import com.duluin.ftth.hotspot.domain.model.VoucherBatch
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import java.time.Duration
import java.util.UUID

data class CreateVoucherBatchCommand(val siteId: UUID, val planId: UUID, val duration: Duration)
data class CreateVoucherCommand(val batchId: UUID?, val username: String, val password: String, val siteId: UUID, val planId: UUID, val duration: Duration)
data class GenerateVoucherBatchCommand(val siteId: UUID, val planId: UUID, val duration: Duration, val quantity: Int)
data class IssuedVoucherCredential(val voucher: Voucher, val password: String)
data class GeneratedVoucherBatch(val batch: VoucherBatch, val credentials: List<IssuedVoucherCredential>)
data class HotspotSessionView(
    val voucherId: UUID,
    val online: Boolean,
    val nasId: UUID?,
    val ipAddress: String?,
    val sessionId: String?,
    val deviceId: String?,
    val startedAt: java.time.Instant?,
    val lastSeenAt: java.time.Instant?,
    val inputBytes: Long?,
    val outputBytes: Long?,
)

interface ManageVoucherUseCase {
    fun createBatch(command: CreateVoucherBatchCommand): VoucherBatch
    fun create(command: CreateVoucherCommand): Voucher
    fun generateBatch(command: GenerateVoucherBatchCommand): GeneratedVoucherBatch
    fun listBatches(siteId: UUID?, page: PageRequest): Page<VoucherBatch>
    fun getBatch(batchId: UUID): VoucherBatch
    fun listVouchers(batchId: UUID?, siteId: UUID?, status: VoucherStatus?, page: PageRequest): Page<Voucher>
    fun getVoucher(voucherId: UUID): Voucher
    fun getSession(voucherId: UUID): HotspotSessionView
    fun claim(voucherId: UUID, deviceId: String): Voucher

    fun revoke(voucherId: UUID, reason: String): Voucher
}
