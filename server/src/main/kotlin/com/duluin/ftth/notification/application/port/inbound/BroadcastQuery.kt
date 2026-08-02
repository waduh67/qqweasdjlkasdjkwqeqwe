package com.duluin.ftth.notification.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import java.time.Instant
import java.util.UUID

/** Riwayat broadcast: apa yang pernah disiarkan, ke berapa pelanggan, dan hasilnya. */
interface BroadcastQuery {

    fun history(request: PageRequest): Page<BroadcastView>

    fun detail(id: UUID): BroadcastDetail
}

/** Ringkasan satu broadcast untuk daftar riwayat. */
data class BroadcastView(
    val id: UUID,
    val incidentId: UUID?,
    val channel: String,
    /** Asal siaran (MANUAL / SUBSCRIPTION_* / INVOICE_* / WORK_ORDER_SCHEDULED / INCIDENT_OPENED). */
    val trigger: String,
    val message: String,
    val recipientCount: Int,
    val sentCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val createdAt: Instant,
)

/** Sebuah broadcast beserta rincian tiap penerimanya. */
data class BroadcastDetail(
    val broadcast: BroadcastView,
    val recipients: List<BroadcastRecipientView>,
)

data class BroadcastRecipientView(
    val customerId: UUID?,
    val customerName: String,
    val phone: String?,
    val status: String,
    val detail: String?,
    val at: Instant,
)
