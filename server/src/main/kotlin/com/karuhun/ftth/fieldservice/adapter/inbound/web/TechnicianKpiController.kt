package com.karuhun.ftth.fieldservice.adapter.inbound.web

import com.karuhun.ftth.fieldservice.application.service.*
import com.karuhun.ftth.iam.FieldTeam
import org.springframework.web.bind.annotation.*
import java.time.YearMonth
import java.util.UUID

@RestController
@RequestMapping("/api/v1/field/kpi")
class TechnicianKpiController(private val service: TechnicianKpiService, private val loader: CalendarLoader) {
    @GetMapping fun read(@RequestParam month: YearMonth,@RequestParam(required=false) team: FieldTeam?,
        @RequestParam(required=false) technicianId: UUID?): TechnicianKpiView {
        val calendar = loader.populate(service.calendar(month))
        return service.read(month,team,technicianId).copy(calendarError=calendar.syncError)
    }
}
