package com.duluin.ftth.inbox.application.service

import com.duluin.ftth.inbox.application.port.outbound.OperatorNotificationRepository
import com.duluin.ftth.inbox.domain.model.OperatorNotification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Menuliskan satu pemberitahuan ke kotak masuk.
 *
 * Komponen tersendiri — bukan method di [InboxEventListener] — karena pemanggilnya berjalan
 * pada fase AFTER_COMMIT, saat transaksi penerbit sudah ditutup: tanpa transaksi baru,
 * penyimpanan ini tak punya tempat berpijak. `REQUIRES_NEW` sekaligus mengurung kegagalan
 * satu pemberitahuan agar tak merembet ke pemberitahuan lain dalam event yang sama.
 */
@Service
class OperatorNotificationRecorder(
    private val notifications: OperatorNotificationRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(notification: OperatorNotification): Boolean = notifications.saveIfAbsent(notification)
}
