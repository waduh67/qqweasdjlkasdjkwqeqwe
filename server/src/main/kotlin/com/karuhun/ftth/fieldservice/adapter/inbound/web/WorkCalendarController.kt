package com.karuhun.ftth.fieldservice.adapter.inbound.web

import com.karuhun.ftth.fieldservice.application.service.*
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/field")
class WorkCalendarController(private val calendar: WorkCalendarService, private val source: HolidaySource, private val loader: CalendarLoader) {
    @GetMapping("/settings") fun settings() = calendar.settings()
    @PutMapping("/settings") fun settings(@RequestBody input: FieldSettingsInput, @RequestHeader("Idempotency-Key") key: UUID) = calendar.saveSettings(input, key)
    @GetMapping("/calendar/{year}") fun calendar(@PathVariable year: Int) = loader.populate(calendar.read(year))
    @PostMapping("/calendar/{year}/holidays")
    fun holiday(@PathVariable year: Int, @RequestBody input: HolidayInput, @RequestHeader("Idempotency-Key") key: UUID) = calendar.saveHoliday(year, input, key)
    @DeleteMapping("/calendar/{year}/holidays/{date}")
    fun delete(@PathVariable year: Int, @PathVariable date: LocalDate, @RequestBody input: CalendarRevisionInput, @RequestHeader("Idempotency-Key") key: UUID) = calendar.deleteHoliday(year, date, input, key)
    @PostMapping("/calendar/{year}/sync")
    fun sync(@PathVariable year: Int, @RequestBody input: CalendarRevisionInput, @RequestHeader("Idempotency-Key") key: UUID): WorkCalendarView {
        calendar.syncReplay(year, input, key)?.let { return it }
        return calendar.sync(year, input, key, source.fetch(year))
    }
}
