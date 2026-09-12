package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.fieldservice.VisitCancellationCause
import com.duluin.ftth.fieldservice.VisitCancelled
import com.duluin.ftth.order.OrderFulfillmentStalled
import com.duluin.ftth.order.OrderFulfillmentStallResolved
import com.duluin.ftth.order.application.port.inbound.OrderPortalAutomationUseCase
import com.duluin.ftth.order.application.port.inbound.ReleaseSystemFlagCommand
import com.duluin.ftth.order.application.port.inbound.SystemFlagCommand
import com.duluin.ftth.order.domain.model.OrderPortalFlag
import com.duluin.ftth.order.domain.model.OrderPortalNarrative
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * PEMICU OTOMATIS penanda portal. Sebelum ini, `WAITING_CUSTOMER` dan `REQUIRES_ATTENTION` hanya
 * bisa dipasang operator lewat tombol — jalurnya ada sejak V184 tapi tak ada satu pun yang
 * memanggilnya, sehingga dua keadaan yang paling sering terjadi tak pernah terlihat siapa pun:
 * kunjungan yang gagal karena pelanggan, dan saga fulfillment yang macet.
 *
 * Pemetaan sebab lapangan → narasi pelanggan tinggal DI SINI, di lapisan yang membatasi module
 * order dari module fieldservice. Menaruhnya di domain akan memaksa agregat pesanan mengenal
 * kosakata kunjungan teknisi, dan setiap sebab baru di lapangan akan menyentuh agregat.
 */
@Component
class OrderPortalAutomationListener(
    private val automation: OrderPortalAutomationUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * BEFORE_COMMIT: penanda portal dan pembatalan kunjungan WAJIB jadi satu fakta. Kalau
     * penandanya ditulis setelah commit lalu gagal, kunjungan tercatat gagal sementara portal
     * pelanggan tetap berkata "sedang dijadwalkan" — persis keadaan yang fitur ini hapus.
     *
     * Aman digabung ke transaksi pemanggil karena [OrderPortalAutomationUseCase] tidak melempar
     * untuk keadaan yang wajar; yang tersisa hanya kegagalan infrastruktur, dan pembatalan
     * kunjungan memang layak ikut batal kalau database-nya sedang tidak bisa ditulisi.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    fun onVisitCancelled(event: VisitCancelled) {
        val narrative = narrativeFor(event.cause)
        if (narrative == null) {
            // Kunjungan yang gagal karena urusan KAMI (blocker teknis, jadwal ulang internal)
            // TIDAK menandai pelanggan. Menyuruh pelanggan bertindak atas keterlambatan yang
            // kami sebabkan adalah cara tercepat kehilangan kepercayaannya.
            log.debug("Kunjungan {} batal karena {} — bukan sebab pelanggan, penanda portal dilewati", event.visitId, event.cause)
            return
        }
        automation.applySystemFlag(
            SystemFlagCommand(
                tenantId = event.tenantId,
                orderId = event.orderId,
                flag = OrderPortalFlag.WAITING_CUSTOMER,
                narrative = narrative,
                source = SOURCE_VISIT,
                reference = event.visitId.toString(),
                // Teknisi yang melaporkan gagalnya adalah pelakunya. Riwayat pesanan yang
                // berkata "ditandai oleh entah siapa" tak bisa ditindaklanjuti supervisor.
                actorId = event.technicianId,
            ),
        )
    }

    /**
     * AFTER_COMMIT, bukan BEFORE_COMMIT — kebalikan dari kunjungan, dan sengaja.
     *
     * Event ini terbit dari BLOK CATCH koordinator saga: exception yang membawanya ke sana
     * biasanya keluar dari bean ber-@Transactional, yang berarti transaksi saga sudah ditandai
     * rollback-only oleh TransactionInterceptor. Menulis penanda di dalam transaksi itu berarti
     * penandanya ikut lenyap saat commit meledak jadi UnexpectedRollbackException — dan lebih
     * buruk, kegagalan penulisan penanda bisa ikut menjatuhkan checkpoint REQUIRES_RECONCILIATION
     * yang justru satu-satunya jejak bahwa saga ini macet.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onFulfillmentStalled(event: OrderFulfillmentStalled) {
        TenantContext.runAs(event.tenantId) {
            automation.applySystemFlag(
                SystemFlagCommand(
                    tenantId = event.tenantId,
                    orderId = event.orderId,
                    flag = OrderPortalFlag.REQUIRES_ATTENTION,
                    narrative = OrderPortalNarrative.FULFILLMENT_NEEDS_REVIEW,
                    source = SOURCE_FULFILLMENT,
                    reference = "${event.namespace}:${event.operationKey}",
                    // Saga berjalan di worker outbox tanpa SecurityContext: tak ada pelaku manusia.
                    actorId = null,
                ),
            )
        }
    }

    /** Kemacetan sudah diselesaikan manusia; penanda yang dipasang KARENA kemacetan itu boleh dilepas. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onFulfillmentStallResolved(event: OrderFulfillmentStallResolved) {
        TenantContext.runAs(event.tenantId) {
            automation.releaseSystemFlag(
                ReleaseSystemFlagCommand(
                    tenantId = event.tenantId,
                    orderId = event.orderId,
                    flag = OrderPortalFlag.REQUIRES_ATTENTION,
                    source = SOURCE_FULFILLMENT,
                    reference = "${event.namespace}:${event.operationKey}",
                ),
            )
        }
    }

    /**
     * `null` = sebabnya BUKAN di sisi pelanggan, jadi pesanannya tidak ditandai sama sekali.
     *
     * `when` ini SENGAJA menyebut setiap nilai satu per satu tanpa `else`: menambah sebab baru
     * di [VisitCancellationCause] harus MEMAKSA seseorang memutuskan kalimat apa yang dibaca
     * pelanggan. Dengan `else`, sebab baru akan diam-diam jatuh ke "bukan urusan pelanggan" dan
     * tak ada yang pernah tahu pemicunya tidak lengkap.
     */
    private fun narrativeFor(cause: VisitCancellationCause): OrderPortalNarrative? = when (cause) {
        VisitCancellationCause.CUSTOMER_NOT_PRESENT -> OrderPortalNarrative.VISIT_CUSTOMER_NOT_PRESENT
        VisitCancellationCause.PREMISE_LOCKED -> OrderPortalNarrative.VISIT_PREMISE_LOCKED
        VisitCancellationCause.CUSTOMER_REFUSED_INSTALL_POINT -> OrderPortalNarrative.VISIT_INSTALL_POINT_REJECTED
        VisitCancellationCause.CUSTOMER_RESCHEDULED -> OrderPortalNarrative.VISIT_RESCHEDULED_BY_CUSTOMER
        VisitCancellationCause.ADDRESS_NOT_FOUND,
        VisitCancellationCause.TECHNICAL_BLOCKER,
        VisitCancellationCause.INTERNAL_RESCHEDULE,
        -> null
    }

    private companion object {
        const val SOURCE_VISIT = "visit-cancelled"
        const val SOURCE_FULFILLMENT = "fulfillment-reconciliation"
    }
}
