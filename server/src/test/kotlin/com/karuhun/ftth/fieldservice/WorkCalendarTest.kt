package com.karuhun.ftth.fieldservice

import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.fieldservice.application.service.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth

class WorkCalendarTest {
    private val source = HolidaySource()
    private val months = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")
    private fun document(year: Int = 2026, count: Int = 12, entry: String = "<span class=kal-libur-day>1–3</span> : Libur bersama") =
        months.take(count).mapIndexed { index, month -> """<section class="kalender-indo"><a class="kal-title-link">$month $year</a>
            <ul class="kal-libur-list"><li>${if (index == 0) entry else "Bulan Tanpa Libur Nasional"}</li></ul></section>""" }.joinToString("")
    @Test fun `parser accepts complete year ranges and empty month markers`() {
        assertThat(source.parse(2026, document())).isEqualTo(mapOf("2026-01-01" to "Libur bersama", "2026-01-02" to "Libur bersama", "2026-01-03" to "Libur bersama"))
    }
    @Test fun `parser rejects incomplete wrong year impossible ranges and unnamed holidays`() {
        listOf(document(count = 11), document(year = 2025), document(entry = "<span class=kal-libur-day>31–32</span> Libur"),
            document(entry = "<span class=kal-libur-day>5–2</span> Libur"), document(entry = "<span class=kal-libur-day>1</span>")).forEach {
            assertThatThrownBy { source.parse(2026, it) }.isInstanceOf(ValidationException::class.java)
        }
        assertThatThrownBy { source.parse(1999, document()) }.isInstanceOf(ValidationException::class.java)
    }
    @Test fun `manual edits removals and moves survive imported replacement`() {
        val year = CalendarYear(2026, imported = mapOf("2026-01-01" to "Imported", "2026-01-02" to "Deleted", "2026-01-03" to "Moved"),
            overrides = mapOf("2026-01-01" to "Nama manual", "2026-01-02" to null, "2026-01-03" to null, "2026-01-05" to "Tanggal baru"))
        assertThat(year.copy(imported = year.imported.mapValues { "Updated" }).view(true).holidays.map { it.name })
            .containsExactly("Nama manual", "Tanggal baru")
    }
    @Test fun `working denominator includes future dates and honors saturday setting`() {
        val calendar = CalendarYear(2026, overrides = mapOf("2026-10-05" to "Libur kantor")).view(true)
        assertThat(calendar.workingDates(YearMonth.of(2026, 10))).hasSize(21).contains(LocalDate.of(2026, 10, 30))
        assertThat(calendar.working(LocalDate.of(2026, 10, 4))).isFalse()
        assertThat(calendar.working(LocalDate.of(2026, 10, 5))).isFalse()
        assertThat(calendar.copy(saturdayOff = false).workingDates(YearMonth.of(2026, 10))).hasSize(26)
    }
}
