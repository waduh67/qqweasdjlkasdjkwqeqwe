package com.karuhun.ftth.fieldservice.adapter.outbound.persistence

import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.fieldservice.application.service.*
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Repository
class DailyReportStore(private val store: OperationalStore) {
    fun get(sql: OperationalSql, report: UUID, revision: Long? = null): DailyReportView = sql.query("""
        SELECT r.*,v.id revision_id,v.revision entry_revision,v.technician_name,v.notes,v.submitted_at FROM field_daily_report r
        JOIN field_daily_report_revision v ON v.tenant_id=r.tenant_id AND v.report_id=r.id AND v.revision=coalesce(?::bigint,r.revision)
        WHERE r.tenant_id=? AND r.id=?""", revision,sql.tenant,report) {
        DailyReportView(it.uuid("id"),it.uuid("technician_id"),it.getString("technician_name"),it.getObject("report_date",LocalDate::class.java),
            ReportSlot.valueOf(it.getString("slot")),it.getLong("entry_revision"),it.uuid("revision_id"),it.getString("notes"),
            it.getTimestamp("submitted_at").toInstant(),emptyList())
    }.singleOrNull()?.let { value ->
        value.copy(photos=sql.query("SELECT id,content_type,size_bytes FROM field_daily_report_photo WHERE tenant_id=? AND revision_id=? ORDER BY id",
            sql.tenant,value.revisionId) { B2BPhotoView(it.uuid("id"),it.getString(2),it.getLong(3)) })
    } ?: throw NotFoundException("Laporan harian tidak ditemukan")
    fun onDate(date: LocalDate, ids: List<UUID>): List<DailyReportView> = store.use { sql ->
        if(ids.isEmpty()) return@use emptyList()
        sql.query("SELECT id FROM field_daily_report WHERE tenant_id=? AND report_date=? AND technician_id IN (${ids.joinToString(",") { "?" }})",
            sql.tenant,date,*ids.toTypedArray()) { it.uuid("id") }.map { get(sql,it) }
    }
    fun history(month: YearMonth, ids: List<UUID>, page: PageRequest): Page<DailyReportView> = store.use { sql ->
        if(ids.isEmpty()) return@use Page(emptyList(),page.page,page.size,0)
        val where = "tenant_id=? AND report_date>=? AND report_date<? AND technician_id IN (${ids.joinToString(",") { "?" }})"
        val args = arrayOf(sql.tenant,month.atDay(1),month.plusMonths(1).atDay(1),*ids.toTypedArray())
        val total = sql.query("SELECT count(*) FROM field_daily_report WHERE $where",*args) { it.getLong(1) }.single()
        val reports = sql.query("SELECT id FROM field_daily_report WHERE $where ORDER BY report_date DESC,slot,id LIMIT ? OFFSET ?",
            *args,page.size,page.page.toLong()*page.size) { it.uuid("id") }.map { get(sql,it) }
        Page(reports,page.page,page.size,total)
    }
    fun credits(month: YearMonth): List<ReportCredit> = store.use { sql ->
        sql.query("SELECT technician_id,report_date,slot FROM field_daily_report WHERE tenant_id=? AND report_date>=? AND report_date<?",
            sql.tenant,month.atDay(1),month.plusMonths(1).atDay(1)) {
            ReportCredit(it.uuid("technician_id"),it.getObject("report_date",LocalDate::class.java),ReportSlot.valueOf(it.getString("slot")))
        }
    }
}
