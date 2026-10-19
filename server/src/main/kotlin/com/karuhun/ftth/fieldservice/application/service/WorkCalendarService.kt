package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.ConflictException
import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.OperationalSql
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.OperationalStore
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.uuid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Timestamp
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional
class WorkCalendarService(private val store: OperationalStore, private val access: FieldAccess, private val clock: B2BOperationalClock) {
    private val mapper = jacksonObjectMapper()
    fun settings(): FieldOperationalSettings { access.owner(access.actor()); return effectiveSettings() }
    fun effectiveSettings(): FieldOperationalSettings = store.use(::settings)
    fun read(year: Int): WorkCalendarView {
        access.scope(access.actor(), "kpi.technician.view", "kpi.team.view")
        return effectiveCalendar(year)
    }
    fun effectiveCalendar(year: Int): WorkCalendarView {
        validateYear(year)
        return store.use { calendar(it, year).view(settings(it).saturdayOff) }
    }
    fun saveSettings(input: FieldSettingsInput, key: UUID): FieldOperationalSettings {
        val actor = access.actor(); access.owner(actor, true)
        if (input.expectedRevision < 0 || input.foOverdueLimit !in 1..1000 || input.foPointTarget !in 1..10000)
            throw ValidationException("Batas WO telat harus 1–1000 dan target poin 1–10000")
        return store.use { sql ->
            command(sql, actor.fence.identity.userId, key, "settings", input, FieldOperationalSettings::class.java) {
                val current = settings(sql)
                revision(current.revision, input.expectedRevision)
                sql.update("""INSERT INTO field_operational_settings(tenant_id,revision,saturday_off,fo_overdue_limit,fo_point_target) VALUES (?,?,?,?,?)
                    ON CONFLICT(tenant_id) DO UPDATE SET revision=excluded.revision,saturday_off=excluded.saturday_off,
                    fo_overdue_limit=excluded.fo_overdue_limit,fo_point_target=excluded.fo_point_target""",
                    sql.tenant, current.revision + 1, input.saturdayOff, input.foOverdueLimit, input.foPointTarget)
                FieldOperationalSettings(current.revision + 1, input.saturdayOff, input.foOverdueLimit, input.foPointTarget)
            }
        }
    }
    fun saveHoliday(year: Int, input: HolidayInput, key: UUID): WorkCalendarView {
        val actor = access.actor(); access.owner(actor, true); validateYear(year)
        val value = input.copy(name = input.name.trim())
        validateYear(value.date.year)
        if ((value.previousDate == null && value.date.year != year) || value.previousDate?.year?.let { it != year } == true || value.name.length !in 1..300)
            throw ValidationException("Pilih tanggal libur yang sesuai dan isi nama (maksimal 300 karakter)")
        return store.use { sql ->
            command(sql, actor.fence.identity.userId, key, "holiday:$year", value, WorkCalendarView::class.java) {
                val current = calendar(sql, year); revision(current.revision, value.expectedRevision)
                val existing = current.view(settings(sql).saturdayOff).holidays.map { it.date }.toSet()
                if (value.previousDate != null && value.previousDate !in existing) throw NotFoundException("Hari libur tidak ditemukan")
                val destination = if (value.date.year == year) current else calendar(sql, value.date.year)
                if (destination.view(settings(sql).saturdayOff).holidays.any { it.date == value.date } && value.date != value.previousDate)
                    throw ConflictException("Tanggal ini sudah memiliki hari libur")
                val overrides = current.overrides.toMutableMap()
                value.previousDate?.takeIf { it != value.date }?.let { overrides[it.toString()] = null }
                if (value.date.year == year) overrides[value.date.toString()] = value.name
                else save(sql, destination.copy(revision = destination.revision + 1, overrides = destination.overrides + (value.date.toString() to value.name)))
                save(sql, current.copy(revision = current.revision + 1, overrides = overrides)).view(settings(sql).saturdayOff)
            }
        }
    }
    fun deleteHoliday(year: Int, date: LocalDate, input: CalendarRevisionInput, key: UUID): WorkCalendarView {
        val actor = access.actor(); access.owner(actor, true); validateYear(year)
        if (date.year != year) throw ValidationException("Tanggal harus pada tahun kalender")
        return store.use { sql ->
            command(sql, actor.fence.identity.userId, key, "delete:$date", input, WorkCalendarView::class.java) {
                val current = calendar(sql, year); revision(current.revision, input.expectedRevision)
                if (current.view(settings(sql).saturdayOff).holidays.none { it.date == date }) throw NotFoundException("Hari libur tidak ditemukan")
                save(sql, current.copy(revision = current.revision + 1, overrides = current.overrides + (date.toString() to null)))
                    .view(settings(sql).saturdayOff)
            }
        }
    }
    fun syncReplay(year: Int, input: CalendarRevisionInput, key: UUID): WorkCalendarView? {
        val actor = access.actor(); access.owner(actor, true); validateYear(year)
        val canonical = mapper.writeValueAsString(mapOf("operation" to "sync:$year", "input" to input))
        return store.use { sql ->
            sql.query("SELECT actor_id,canonical,result::text FROM field_settings_command WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
                Triple(it.uuid("actor_id"), it.getString(2), it.getString(3))
            }.singleOrNull()?.let {
                if (it.first != actor.fence.identity.userId || it.second != canonical) throw ConflictException("Kunci pengiriman sudah dipakai untuk perubahan lain")
                mapper.readValue(it.third, WorkCalendarView::class.java)
            }
        }
    }
    fun sync(year: Int, input: CalendarRevisionInput, key: UUID, imported: Map<String, String>): WorkCalendarView {
        val actor = access.actor(); access.owner(actor, true); validateYear(year)
        return store.use { sql ->
            command(sql, actor.fence.identity.userId, key, "sync:$year", input, WorkCalendarView::class.java) {
                val current = calendar(sql, year); revision(current.revision, input.expectedRevision)
                save(sql, current.copy(revision = current.revision + 1, imported = imported, refreshedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)))
                    .view(settings(sql).saturdayOff)
            }
        }
    }
    fun refresh(year: Int, imported: Map<String, String>, initial: Boolean = false): WorkCalendarView = store.use { sql ->
        validateYear(year); sql.lock()
        val current = calendar(sql, year)
        val result = if (initial && current.refreshedAt != null) current else
            save(sql, current.copy(revision = current.revision + 1, imported = imported, refreshedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)))
        result.view(settings(sql).saturdayOff)
    }
    private fun settings(sql: OperationalSql): FieldOperationalSettings = sql.query(
        "SELECT revision,saturday_off,fo_overdue_limit,fo_point_target FROM field_operational_settings WHERE tenant_id=?", sql.tenant) {
        FieldOperationalSettings(it.getLong(1), it.getBoolean(2), it.getInt(3), it.getInt(4))
    }.singleOrNull() ?: FieldOperationalSettings()
    private fun calendar(sql: OperationalSql, year: Int): CalendarYear = sql.query(
        "SELECT revision,imported::text,overrides::text,refreshed_at FROM field_work_calendar_year WHERE tenant_id=? AND year=?", sql.tenant, year) {
        val imported = mapper.readTree(it.getString(2)).properties().associate { entry -> entry.key to entry.value.asString() }
        val overrides = mapper.readTree(it.getString(3)).properties().associate { entry -> entry.key to if (entry.value.isNull) null else entry.value.asString() }
        CalendarYear(year, it.getLong(1), imported, overrides, it.getTimestamp(4)?.toInstant())
    }.singleOrNull() ?: CalendarYear(year)
    private fun save(sql: OperationalSql, year: CalendarYear): CalendarYear {
        sql.update("""INSERT INTO field_work_calendar_year(tenant_id,year,revision,imported,overrides,refreshed_at) VALUES (?,?,?,?::jsonb,?::jsonb,?)
            ON CONFLICT(tenant_id,year) DO UPDATE SET revision=excluded.revision,imported=excluded.imported,
            overrides=excluded.overrides,refreshed_at=excluded.refreshed_at""", sql.tenant, year.year, year.revision,
            mapper.writeValueAsString(year.imported), mapper.writeValueAsString(year.overrides), year.refreshedAt?.let(Timestamp::from))
        return year
    }
    private fun <T: Any> command(sql: OperationalSql, actor: UUID, key: UUID, operation: String, input: Any, type: Class<T>, action: () -> T): T {
        sql.lock()
        val canonical = mapper.writeValueAsString(mapOf("operation" to operation, "input" to input))
        sql.query("SELECT actor_id,canonical,result::text FROM field_settings_command WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            Triple(it.uuid("actor_id"), it.getString(2), it.getString(3))
        }.singleOrNull()?.let {
            if (it.first != actor || it.second != canonical) throw ConflictException("Kunci pengiriman sudah dipakai untuk perubahan lain")
            return mapper.readValue(it.third, type)
        }
        val result = action()
        sql.update("INSERT INTO field_settings_command(tenant_id,operation_key,actor_id,canonical,result) VALUES (?,?,?,?,?::jsonb)",
            sql.tenant, key, actor, canonical, mapper.writeValueAsString(result))
        return result
    }
    private fun revision(actual: Long, expected: Long) {
        if (expected < 0) throw ValidationException("Revisi harus nol atau lebih")
        if (actual != expected) throw ConflictException("Pengaturan berubah. Muat ulang sebelum menyimpan")
    }
    private fun validateYear(year: Int) { if (year !in 2000..2100) throw ValidationException("Tahun kalender harus 2000 sampai 2100") }
}
