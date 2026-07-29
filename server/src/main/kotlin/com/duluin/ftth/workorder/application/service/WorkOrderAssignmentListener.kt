package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.workorder.WorkOrderAssigned
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Titik kait untuk push/notifikasi aplikasi teknisi mobile saat sebuah work order
 * ditugaskan. Untuk sekarang hanya mencatat log — dispatcher notifikasi sungguhan
 * (mis. FCM/WhatsApp lewat module notification) tinggal menambah listener sejenis
 * atas [WorkOrderAssigned] tanpa menyentuh module workorder.
 *
 * Berjalan pada AFTER_COMMIT agar hanya assignment yang benar ter-commit yang
 * memicu notifikasi. `fallbackExecution = true` menjaga jalur non-transaksional.
 */
@Component
class WorkOrderAssignmentListener {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: WorkOrderAssigned) {
        log.info(
            "Work order {} ({}) ditugaskan ke teknisi {} — siap dikirim ke aplikasi teknisi",
            event.code,
            event.workOrderId,
            event.technicianId,
        )
    }
}
