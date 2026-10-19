package com.karuhun.ftth.fieldservice.application.service

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class FieldOperationalSettings(val revision: Long = 0, val saturdayOff: Boolean = true,
    val foOverdueLimit: Int = 4, val foPointTarget: Int = 15)
data class FieldSettingsInput(val expectedRevision: Long, val saturdayOff: Boolean, val foOverdueLimit: Int, val foPointTarget: Int)
data class WorkHoliday(val date: LocalDate, val name: String, val source: String)
data class WorkCalendarView(val year: Int, val revision: Long, val saturdayOff: Boolean,
    val refreshedAt: Instant?, val holidays: List<WorkHoliday>, val syncError: String? = null) {
    fun working(date: LocalDate): Boolean = date.dayOfWeek != DayOfWeek.SUNDAY &&
        (!saturdayOff || date.dayOfWeek != DayOfWeek.SATURDAY) && holidays.none { it.date == date }
    fun workingDates(month: YearMonth): Set<LocalDate> = (1..month.lengthOfMonth()).map(month::atDay).filter(::working).toSet()
}
data class HolidayInput(val expectedRevision: Long, val date: LocalDate, val name: String, val previousDate: LocalDate? = null)
data class CalendarRevisionInput(val expectedRevision: Long)
internal data class CalendarYear(val year: Int, val revision: Long = 0, val imported: Map<String, String> = emptyMap(),
    val overrides: Map<String, String?> = emptyMap(), val refreshedAt: Instant? = null) {
    fun view(saturdayOff: Boolean): WorkCalendarView {
        val combined = imported.mapValues { (date, name) -> WorkHoliday(LocalDate.parse(date), name, "IMPORTED") }.toMutableMap()
        overrides.forEach { (date, name) -> if (name == null) combined.remove(date) else combined[date] = WorkHoliday(LocalDate.parse(date), name, "MANUAL") }
        return WorkCalendarView(year, revision, saturdayOff, refreshedAt, combined.values.sortedBy { it.date })
    }
}
