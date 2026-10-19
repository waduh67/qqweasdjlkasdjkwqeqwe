package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.common.domain.error.ConflictException
import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.DailyReportStore
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.OperationalStore
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.uuid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Timestamp
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Service
@Transactional
class DailyReportService(private val store: OperationalStore, private val reports: DailyReportStore, private val access: FieldAccess,
    private val calendar: WorkCalendarService, private val clock: B2BOperationalClock, private val photos: FieldPhotoStorage) {
    private val mapper = jacksonObjectMapper()
    fun calendar(date: LocalDate): WorkCalendarView {
        if(date.year<2000 || date>clock.today()) throw ValidationException("Pilih tanggal hingga hari ini")
        scope(null)
        return calendar.effectiveCalendar(date.year)
    }
    fun submit(date: LocalDate, slot: ReportSlot, notes: String, expectedRevision: Long, files: List<B2BPhotoInput>, key: UUID): DailyReportView {
        val actor = access.actor(); val technician = access.technician(actor,"field.report.submit")
        val text = notes.trim()
        if(text.length !in 1..5000 || expectedRevision<0 || files.size>5) throw ValidationException("Isi catatan maksimal 5000 karakter dan lampirkan maksimal 5 foto")
        files.forEach(photos::validate)
        val hash = fieldHash(mapper.writeValueAsBytes(mapOf("date" to date,"slot" to slot,"notes" to text,"revision" to expectedRevision,
            "photos" to files.map { mapOf("type" to it.contentType,"hash" to fieldHash(it.bytes)) })))
        return store.use { sql ->
            sql.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","report-key:${sql.tenant}:$key") { true }
            sql.query("""SELECT v.report_id,v.revision,v.payload_hash,r.technician_id FROM field_daily_report_revision v
                JOIN field_daily_report r ON r.tenant_id=v.tenant_id AND r.id=v.report_id WHERE v.tenant_id=? AND v.operation_key=?""",sql.tenant,key) {
                Pair(it.uuid("report_id") to it.getLong("revision"),it.getString("payload_hash") to it.uuid("technician_id"))
            }.singleOrNull()?.let {
                if(it.second.first != hash || it.second.second != technician.id) throw ConflictException("Kunci pengiriman sudah dipakai untuk laporan lain")
                return@use reports.get(sql,it.first.first,it.first.second)
            }
            if(date != clock.today()) throw ValidationException("Laporan hanya dapat dibuat atau diubah untuk hari ini")
            sql.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","report:${sql.tenant}:${technician.id}:$date:$slot") { true }
            val prior = sql.query("SELECT id,revision FROM field_daily_report WHERE tenant_id=? AND technician_id=? AND report_date=? AND slot=?",
                sql.tenant,technician.id,date,slot.name) { it.uuid("id") to it.getLong("revision") }.singleOrNull()
            if(expectedRevision != (prior?.second ?: 0L)) throw ConflictException("Laporan berubah. Muat ulang sebelum menyimpan")
            val report = prior?.first ?: UUID.randomUUID(); val revision = expectedRevision+1; val entry = UUID.randomUUID()
            if(prior==null) sql.update("INSERT INTO field_daily_report(id,tenant_id,technician_id,report_date,slot,revision) VALUES (?,?,?,?,?,?)",
                report,sql.tenant,technician.id,date,slot.name,revision)
            else sql.update("UPDATE field_daily_report SET revision=? WHERE tenant_id=? AND id=?",revision,sql.tenant,report)
            sql.update("""INSERT INTO field_daily_report_revision(id,tenant_id,report_id,revision,technician_name,notes,submitted_at,operation_key,payload_hash)
                VALUES (?,?,?,?,?,?,?,?,?)""",entry,sql.tenant,report,revision,technician.name,text,Timestamp.from(clock.instant()),key,hash)
            files.forEach { file ->
                val id = UUID.randomUUID(); val objectKey = "${sql.tenant}/daily-reports/$entry/$id"
                val photoHash = photos.put(sql.tenant,objectKey,file)
                sql.update("INSERT INTO field_daily_report_photo(id,tenant_id,revision_id,object_key,content_type,size_bytes,sha256) VALUES (?,?,?,?,?,?,?)",
                    id,sql.tenant,entry,objectKey,file.contentType,file.bytes.size.toLong(),photoHash)
            }
            reports.get(sql,report,revision)
        }
    }
    fun completeness(date: LocalDate, technicianId: UUID?): DailyCompleteness {
        if(date.year<2000 || date>clock.today()) throw ValidationException("Pilih tanggal hingga hari ini")
        val technicians = scope(null)
        if (technicianId != null && technicians.none { it.id == technicianId }) throw NotFoundException("Teknisi tidak ditemukan")
        val members = technicians.filter { technicianId == null || it.id == technicianId }
        val saved = reports.onDate(date,members.map { it.id })
        val rows = members.map { member -> DailyCompletenessRow(member, saved.singleOrNull { it.technicianId==member.id && it.slot==ReportSlot.MORNING },
            saved.singleOrNull { it.technicianId==member.id && it.slot==ReportSlot.EVENING }) }
        val working = calendar.effectiveCalendar(date.year).working(date)
        return DailyCompleteness(date,working,rows.size,rows.count { it.morning!=null },rows.count { it.evening!=null },
            if(working) rows.count { it.morning==null || it.evening==null } else 0,rows,technicians)
    }
    fun history(month: YearMonth, technicianId: UUID?, page: PageRequest) =
        reports.history(validMonth(month),scope(technicianId).map { it.id },page)
    fun timeline(id: UUID): List<DailyReportView> {
        val ids = scope(null).map { it.id }.toSet()
        return store.use { sql ->
            val current = reports.get(sql,id)
            if(current.technicianId !in ids) throw NotFoundException("Laporan harian tidak ditemukan")
            sql.query("SELECT revision FROM field_daily_report_revision WHERE tenant_id=? AND report_id=? ORDER BY revision DESC",sql.tenant,id) {
                it.getLong(1)
            }.map { reports.get(sql,id,it) }
        }
    }
    fun photo(report: UUID, revision: Long, id: UUID): B2BPhotoInput {
        val ids = scope(null).map { it.id }.toSet()
        return store.use { sql ->
            val entry = reports.get(sql,report,revision)
            if(entry.technicianId !in ids) throw NotFoundException("Bukti laporan tidak ditemukan")
            sql.query("SELECT object_key,content_type,size_bytes,sha256 FROM field_daily_report_photo WHERE tenant_id=? AND revision_id=? AND id=?",
                sql.tenant,entry.revisionId,id) { photos.read(it.getString(1),it.getString(2),it.getLong(3),it.getString(4)) }.singleOrNull()
                ?: throw NotFoundException("Bukti laporan tidak ditemukan")
        }
    }
    private fun scope(id: UUID?) = access.scope(access.actor(),"field.report.view","field.report.all").let { members ->
        if(id!=null && members.none { it.id==id }) throw NotFoundException("Teknisi tidak ditemukan")
        members.filter { id==null || it.id==id }
    }
    private fun validMonth(month: YearMonth): YearMonth {
        if(month.year<2000 || month>YearMonth.from(clock.today())) throw ValidationException("Pilih bulan hingga bulan ini")
        return month
    }
}
