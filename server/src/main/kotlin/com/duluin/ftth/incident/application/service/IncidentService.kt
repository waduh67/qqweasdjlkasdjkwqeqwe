package com.duluin.ftth.incident.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.incident.IncidentApi
import com.duluin.ftth.incident.application.port.inbound.IncidentDetail
import com.duluin.ftth.incident.application.port.inbound.IncidentEventView
import com.duluin.ftth.incident.application.port.inbound.IncidentQuery
import com.duluin.ftth.incident.application.port.inbound.IncidentView
import com.duluin.ftth.incident.application.port.inbound.ManageIncidentUseCase
import com.duluin.ftth.incident.application.port.outbound.IncidentRepository
import com.duluin.ftth.incident.domain.model.Incident
import com.duluin.ftth.incident.domain.model.IncidentEvent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class IncidentService(
    private val repository: IncidentRepository,
    private val correlation: IncidentCorrelationService,
    private val currentUser: CurrentUserProvider,
    private val incidentApi: IncidentApi,
) : IncidentQuery, ManageIncidentUseCase {

    override fun activeIncidents(): List<IncidentView> = repository.findOpen().map { it.toView() }

    // Reuse penurunan pelanggan-terdampak yang dipakai broadcast (akar → hilir), lalu
    // saring ke satu pelanggan. Menjamin konsistensi: pelanggan melihat persis insiden
    // yang siarannya akan menjangkau dia. Insiden terbuka jumlahnya sedikit → murah.
    override fun incidentsForCustomer(customerId: UUID): List<IncidentView> =
        repository.findOpen()
            .filter { inc -> incidentApi.affectedContacts(inc.id).any { it.customerId == customerId } }
            .map { it.toView() }

    override fun incident(id: UUID): IncidentDetail {
        val incident = repository.findById(id) ?: throw NotFoundException("Insiden $id tidak ditemukan")
        val timeline = repository.timelineOf(id).map { it.toView() }
        // Anggota alarm dihitung ulang dari keadaan hidup — hanya bermakna selagi
        // insidennya masih terbuka; yang sudah selesai diceritakan oleh timeline.
        val members = if (incident.status.open) {
            correlation.correlate().firstOrNull { it.key == "${incident.rootType}:${incident.rootId}" }?.members.orEmpty()
        } else {
            emptyList()
        }
        return IncidentDetail(incident.toView(), timeline, members)
    }

    @Transactional
    override fun acknowledge(id: UUID): IncidentView {
        val incident = require(id)
        incident.acknowledge(currentUser.current().userId)
        return repository.save(incident).toView()
    }

    @Transactional
    override fun resolve(id: UUID): IncidentView {
        val incident = require(id)
        incident.resolve(auto = false, actorId = currentUser.current().userId)
        return repository.save(incident).toView()
    }

    private fun require(id: UUID): Incident =
        repository.findById(id) ?: throw NotFoundException("Insiden $id tidak ditemukan")

    private fun Incident.toView() = IncidentView(
        id = id,
        key = "$rootType:$rootId",
        rootType = rootType.name,
        rootId = rootId,
        rootLabel = rootLabel,
        severity = severity.name,
        status = status.name,
        title = title,
        alarmCount = alarmCount,
        affectedCustomerCount = affectedCustomerCount,
        suspectedCause = suspectedCause?.name,
        openedAt = openedAt,
        lastSeenAt = lastSeenAt,
        acknowledgedAt = acknowledgedAt,
        resolvedAt = resolvedAt,
    )

    private fun IncidentEvent.toView() = IncidentEventView(type = type.name, message = message, at = at)
}
