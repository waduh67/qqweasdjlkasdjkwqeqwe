package com.duluin.ftth.incident.application.port.outbound

import com.duluin.ftth.incident.domain.model.Incident
import com.duluin.ftth.incident.domain.model.IncidentEvent
import java.util.UUID

interface IncidentRepository {

    /** Menyimpan agregat beserta event timeline yang tertunda, lalu mengosongkannya. */
    fun save(incident: Incident): Incident

    fun findById(id: UUID): Incident?

    /** Insiden yang belum selesai (status <> RESOLVED) untuk tenant aktif, terparah lebih dulu. */
    fun findOpen(): List<Incident>

    /** Timeline sebuah insiden, terlama lebih dulu. */
    fun timelineOf(incidentId: UUID): List<IncidentEvent>
}
