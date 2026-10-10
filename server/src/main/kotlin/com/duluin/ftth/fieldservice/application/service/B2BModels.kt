package com.duluin.ftth.fieldservice.application.service

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class B2BClientInput(val name: String, val address: String, val contact: String, val technicianId: UUID,
    val target: Int, val active: Boolean = true, val expectedRevision: Long = 0)
data class B2BSetting(val name: String, val address: String, val contact: String, val technicianId: UUID,
    val technicianName: String, val target: Int, val active: Boolean)
data class B2BWeek(val start: LocalDate, val end: LocalDate, val counted: Int, val missed: Boolean)
data class B2BMonth(val clientId: UUID, val month: LocalDate, val setting: B2BSetting, val counted: Int,
    val remaining: Int, val weeks: List<B2BWeek>)
data class B2BClientView(val id: UUID, val revision: Long, val createdMonth: LocalDate,
    val current: B2BMonth, val next: B2BSetting)
data class B2BPhotoView(val id: UUID, val contentType: String, val sizeBytes: Long)
data class B2BVisitView(val id: UUID, val clientId: UUID, val clientName: String, val visitDate: LocalDate,
    val counted: Boolean, val notes: String, val reporterName: String, val createdAt: Instant, val photos: List<B2BPhotoView>)
data class B2BPhotoInput(val contentType: String, val bytes: ByteArray)
data class B2BPhotoRecord(val id: UUID, val visitId: UUID, val key: String, val contentType: String,
    val size: Long, val hash: String)

@Component
class B2BOperationalClock {
    fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Jakarta"))
    fun instant(): Instant = Instant.now()
}
