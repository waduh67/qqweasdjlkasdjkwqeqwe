package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.iam.FieldTechnician
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ReportSlot { MORNING, EVENING }
data class DailyReportView(val id: UUID, val technicianId: UUID, val technicianName: String, val date: LocalDate, val slot: ReportSlot,
    val revision: Long, val revisionId: UUID, val notes: String, val submittedAt: Instant, val photos: List<B2BPhotoView>)
data class DailyCompletenessRow(val technician: FieldTechnician, val morning: DailyReportView?, val evening: DailyReportView?)
data class DailyCompleteness(val date: LocalDate, val workingDay: Boolean, val total: Int, val morning: Int, val evening: Int,
    val incomplete: Int, val rows: List<DailyCompletenessRow>, val technicians: List<FieldTechnician>, val calendarError: String? = null)
data class ReportCredit(val technicianId: UUID, val date: LocalDate, val slot: ReportSlot)
