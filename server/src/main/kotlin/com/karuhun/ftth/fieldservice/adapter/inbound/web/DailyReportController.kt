package com.karuhun.ftth.fieldservice.adapter.inbound.web

import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.fieldservice.application.service.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@RestController
@RequestMapping("/api/v1/field/reports")
class DailyReportController(private val service: DailyReportService, private val loader: CalendarLoader) {
    @GetMapping("/completeness")
    fun completeness(@RequestParam date: LocalDate,@RequestParam(required=false) technicianId: UUID?): DailyCompleteness {
        val calendar = loader.populate(service.calendar(date))
        return service.completeness(date,technicianId).copy(calendarError=calendar.syncError)
    }
    @GetMapping
    fun history(@RequestParam month: YearMonth,@RequestParam(required=false) technicianId: UUID?,
        @RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int) = service.history(month,technicianId,PageRequest(page,size))
    @PostMapping(consumes=[MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submit(@RequestParam date: LocalDate,@RequestParam slot: ReportSlot,@RequestParam notes: String,@RequestParam expectedRevision: Long,
        @RequestParam(required=false) photos: List<MultipartFile>?,@RequestHeader("Idempotency-Key") key: UUID) =
        ResponseEntity.status(201).body(service.submit(date,slot,notes,expectedRevision,photos.orEmpty().map { B2BPhotoInput(it.contentType.orEmpty(),it.bytes) },key))
    @GetMapping("/{id}/revisions")
    fun timeline(@PathVariable id: UUID) = service.timeline(id)
    @GetMapping("/{report}/revisions/{revision}/photos/{id}")
    fun photo(@PathVariable report: UUID,@PathVariable revision: Long,@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val photo = service.photo(report,revision,id)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType)).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline").body(photo.bytes)
    }
}
