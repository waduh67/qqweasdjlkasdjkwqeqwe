package com.duluin.ftth.hotspot.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.hotspot.domain.model.Voucher
import com.duluin.ftth.hotspot.domain.model.VoucherBatch
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class VoucherClaim(val voucher: Voucher, val activated: Boolean)

interface VoucherRepository {
    fun save(voucher: Voucher, password: String? = null): Voucher
    fun findById(id: UUID): Voucher?
    /**
     * Claims under the repository's voucher lock. [VoucherClaim.activated] is true only for
     * the AVAILABLE-to-ACTIVE transition, allowing delivery retries to avoid duplicate actions.
     */
    fun claim(id: UUID, deviceId: String, clock: Clock): VoucherClaim?
    fun expireIfDue(id: UUID, now: Instant): Voucher?
    fun findActiveExpired(now: Instant, limit: Int): List<Voucher>
    fun search(batchId: UUID?, siteId: UUID?, status: VoucherStatus?, page: PageRequest): Page<Voucher>
}

interface VoucherBatchRepository {
    fun save(batch: VoucherBatch): VoucherBatch
    fun findById(id: UUID): VoucherBatch?
    fun search(siteId: UUID?, page: PageRequest): Page<VoucherBatch>
}
