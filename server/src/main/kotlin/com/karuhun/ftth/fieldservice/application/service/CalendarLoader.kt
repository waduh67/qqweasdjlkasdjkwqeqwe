package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.ValidationException
import org.springframework.stereotype.Component

@Component
class CalendarLoader(private val calendar: WorkCalendarService, private val source: HolidaySource) {
    fun populate(current: WorkCalendarView): WorkCalendarView {
        if(current.refreshedAt != null) return current
        return try { calendar.refresh(current.year,source.fetch(current.year),initial=true) }
        catch(failure: ValidationException) { current.copy(syncError=failure.message) }
    }
}
