package com.duluin.ftth.fieldservice.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.fieldservice.application.service.*
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Repository
class B2BStore(private val jdbc: TenantTransactionJdbc) {
    fun <T> use(action: (B2BSql) -> T): T {
        var result: Result<T>? = null
        jdbc.withinTenant(TenantContext.tenantId()) { result = Result.success(action(B2BSql(it, TenantContext.tenantId()))) }
        return requireNotNull(result).getOrThrow()
    }
}

class B2BSql(private val connection: Connection, val tenant: UUID) {
    fun lock() { query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "b2b:$tenant") { true } }
    fun update(sql: String, vararg args: Any?): Int = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }
    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
    }
    fun exists(id: UUID): Boolean = query("SELECT id FROM b2b_client WHERE tenant_id=? AND id=?", tenant,id) { true }.isNotEmpty()
    fun <T> page(sql: String, page: PageRequest, vararg args: Any?, map: (ResultSet) -> T): Page<T> {
        val total = query("SELECT count(*) FROM ($sql) matching", *args) { it.getLong(1) }.single()
        val rows = query("$sql LIMIT ? OFFSET ?", *args, page.size, page.page.toLong() * page.size, map=map)
        return Page(rows, page.page, page.size, total)
    }
    fun prepare(until: LocalDate) {
        val clients = query("SELECT id,created_month FROM b2b_client WHERE tenant_id=? AND created_month<=?",tenant,until) {
            it.uuid("id") to it.getObject("created_month", LocalDate::class.java)
        }
        clients.forEach { (id, created) ->
            val last = query("SELECT max(month) last FROM b2b_client_month WHERE tenant_id=? AND client_id=?",tenant,id) {
                it.getObject("last", LocalDate::class.java)
            }.single()
            var month = last?.plusMonths(1) ?: created
            while (month <= until) {
                update("""INSERT INTO b2b_client_month(tenant_id,client_id,month,name,address,contact,technician_id,technician_name,target,active)
                    SELECT s.tenant_id,s.client_id,?,s.name,s.address,s.contact,s.technician_id,u.name,s.target,s.active
                    FROM b2b_client_setting s JOIN app_user u ON u.tenant_id=s.tenant_id AND u.id=s.technician_id
                    WHERE s.tenant_id=? AND s.client_id=? AND s.effective_month<=?
                    ORDER BY s.effective_month DESC LIMIT 1 ON CONFLICT DO NOTHING""",month,tenant,id,month)
                month = month.plusMonths(1)
            }
        }
    }
    fun setting(id: UUID, effective: LocalDate): B2BSetting = query("""SELECT s.*,u.name technician_name FROM b2b_client_setting s
        JOIN app_user u ON u.tenant_id=s.tenant_id AND u.id=s.technician_id
        WHERE s.tenant_id=? AND s.client_id=? AND s.effective_month<=? ORDER BY effective_month DESC LIMIT 1""",tenant,id,effective, map=::setting).singleOrNull()
        ?: throw NotFoundException("Client B2B tidak ditemukan")
    fun month(id: UUID, month: LocalDate, today: LocalDate): B2BMonth {
        val value = query("SELECT * FROM b2b_client_month WHERE tenant_id=? AND client_id=? AND month=?",tenant,id,month,map=::setting).singleOrNull()
            ?: throw NotFoundException("Client belum ada pada bulan ini")
        val days = query("SELECT visit_date FROM b2b_visit WHERE tenant_id=? AND client_id=? AND month=? AND counted",tenant,id,month) {
            it.getObject("visit_date", LocalDate::class.java)
        }
        val end = YearMonth.from(month).atEndOfMonth()
        var start = month
        val weeks = buildList {
            while (start <= end) {
                val finish = minOf(start.plusDays((7-start.dayOfWeek.value).toLong()), end)
                val count = days.count { it >= start && it <= finish }
                add(B2BWeek(start,finish,count,value.active && finish < today && count == 0))
                start = finish.plusDays(1)
            }
        }
        return B2BMonth(id,month,value,days.size,if(value.active) maxOf(0,value.target-days.size) else 0,weeks)
    }
    fun client(id: UUID, today: LocalDate): B2BClientView {
        val currentMonth=today.withDayOfMonth(1)
        val row=query("SELECT revision,created_month FROM b2b_client WHERE tenant_id=? AND id=?",tenant,id) {
            it.getLong("revision") to it.getObject("created_month",LocalDate::class.java)
        }.singleOrNull() ?: throw NotFoundException("Client B2B tidak ditemukan")
        return B2BClientView(id,row.first,row.second,month(id,currentMonth,today),setting(id,currentMonth.plusMonths(1)))
    }
    fun saveSetting(id: UUID, effective: LocalDate, input: B2BClientInput) {
        update("""INSERT INTO b2b_client_setting(tenant_id,client_id,effective_month,name,address,contact,technician_id,target,active)
            VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,client_id,effective_month) DO UPDATE SET name=excluded.name,
            address=excluded.address,contact=excluded.contact,technician_id=excluded.technician_id,target=excluded.target,active=excluded.active""",
            tenant,id,effective,input.name,input.address,input.contact,input.technicianId,input.target,input.active)
    }
    fun visit(id: UUID): B2BVisitView = query("""SELECT v.*,m.name client_name FROM b2b_visit v JOIN b2b_client_month m
        ON m.tenant_id=v.tenant_id AND m.client_id=v.client_id AND m.month=v.month WHERE v.tenant_id=? AND v.id=?""",tenant,id) {
        B2BVisitView(it.uuid("id"),it.uuid("client_id"),it.getString("client_name"),it.getObject("visit_date",LocalDate::class.java),
            it.getBoolean("counted"),it.getString("notes"),it.getString("reporter_name"),it.getTimestamp("created_at").toInstant(),emptyList())
    }.singleOrNull()?.let { it.copy(photos=photos(id).map { p -> B2BPhotoView(p.id,p.contentType,p.size) }) }
        ?: throw NotFoundException("Visit B2B tidak ditemukan")
    fun photos(visit: UUID): List<B2BPhotoRecord> = query("SELECT * FROM b2b_visit_photo WHERE tenant_id=? AND visit_id=? ORDER BY id",tenant,visit) {
        B2BPhotoRecord(it.uuid("id"),visit,it.getString("object_key"),it.getString("content_type"),it.getLong("size_bytes"),it.getString("sha256"))
    }
    private fun setting(row: ResultSet)=B2BSetting(row.getString("name"),row.getString("address"),row.getString("contact"),
        row.uuid("technician_id"),row.getString("technician_name"),row.getInt("target"),row.getBoolean("active"))
}
internal fun ResultSet.uuid(column: String): UUID = getObject(column,UUID::class.java)
