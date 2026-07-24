package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.incident.IncidentApi
import com.duluin.ftth.notification.application.port.inbound.BroadcastDetail
import com.duluin.ftth.notification.application.port.inbound.BroadcastQuery
import com.duluin.ftth.notification.application.port.inbound.BroadcastRecipientView
import com.duluin.ftth.notification.application.port.inbound.BroadcastView
import com.duluin.ftth.notification.application.port.inbound.SendBroadcastUseCase
import com.duluin.ftth.notification.application.port.inbound.SendIncidentBroadcastCommand
import com.duluin.ftth.notification.application.port.outbound.BroadcastDigest
import com.duluin.ftth.notification.application.port.outbound.BroadcastRepository
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.domain.model.Broadcast
import com.duluin.ftth.notification.domain.model.BroadcastRecipient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menyusun dan menyiarkan broadcast, lalu menyimpannya sebagai riwayat.
 *
 * "Siapa yang terdampak" bukan urusan module ini — itu dijawab incident lewat
 * [IncidentApi.affectedContacts]. Di sini pesannya dikirim satu per satu lewat
 * [MessageDispatcher] dan tiap hasilnya dicatat, sehingga riwayat mencerminkan
 * kenyataan pengiriman, bukan sekadar niat.
 */
@Service
@Transactional(readOnly = true)
class BroadcastService(
    private val incidentApi: IncidentApi,
    private val repository: BroadcastRepository,
    private val dispatcher: MessageDispatcher,
    private val currentUser: CurrentUserProvider,
) : SendBroadcastUseCase, BroadcastQuery {

    @Transactional
    override fun broadcastForIncident(command: SendIncidentBroadcastCommand): BroadcastView {
        val actor = currentUser.current()
        // Melempar NotFound bila insidennya tak ada — validasi keberadaan sekaligus.
        val contacts = incidentApi.affectedContacts(command.incidentId)

        val broadcast = Broadcast.compose(
            tenantId = actor.tenantId,
            incidentId = command.incidentId,
            channel = command.channel,
            message = command.message,
            createdBy = actor.userId,
        )
        contacts.forEach { contact ->
            val outcome = dispatcher.send(command.channel, contact.phone, command.message)
            broadcast.record(
                customerId = contact.customerId,
                customerName = contact.name,
                phone = contact.phone,
                status = outcome.status,
                detail = outcome.detail,
            )
        }
        return repository.save(broadcast).toView()
    }

    override fun history(request: PageRequest): Page<BroadcastView> =
        repository.recent(request).map { it.toView() }

    override fun detail(id: UUID): BroadcastDetail {
        val broadcast = repository.findById(id) ?: throw NotFoundException("Broadcast $id tidak ditemukan")
        return BroadcastDetail(broadcast.toView(), broadcast.recipients.map { it.toView() })
    }

    private fun Broadcast.toView() = BroadcastView(
        id = id,
        incidentId = incidentId,
        channel = channel.name,
        message = message,
        recipientCount = recipientCount,
        sentCount = sentCount,
        skippedCount = skippedCount,
        failedCount = failedCount,
        createdAt = createdAt,
    )

    private fun BroadcastDigest.toView() = BroadcastView(
        id = id,
        incidentId = incidentId,
        channel = channel.name,
        message = message,
        recipientCount = recipientCount,
        sentCount = sentCount,
        skippedCount = skippedCount,
        failedCount = failedCount,
        createdAt = createdAt,
    )

    private fun BroadcastRecipient.toView() = BroadcastRecipientView(
        customerId = customerId,
        customerName = customerName,
        phone = phone,
        status = status.name,
        detail = detail,
        at = at,
    )
}
