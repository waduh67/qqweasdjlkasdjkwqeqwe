package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.ValidationException
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

@Component
class HolidaySource {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun fetch(year: Int): Map<String, String> {
        if (year !in 2000..2100) throw ValidationException("Tahun kalender harus 2000 sampai 2100")
        val request = HttpRequest.newBuilder(URI.create("https://tanggalans.com/kalender-$year/"))
            .timeout(Duration.ofSeconds(20)).header("User-Agent", "FTTH-WorkCalendar/1.0").GET().build()
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200 || response.body().length > 2_000_000) throw ValidationException("Sumber kalender tidak dapat dibaca")
            return parse(year, response.body())
        } catch (failure: java.io.IOException) {
            throw ValidationException("Gagal menghubungi sumber kalender. Kalender tersimpan tetap berlaku")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ValidationException("Sinkronisasi kalender terhenti")
        }
    }
    fun parse(year: Int, html: String): Map<String, String> {
        if (year !in 2000..2100) throw ValidationException("Tahun kalender harus 2000 sampai 2100")
        val names = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")
        val seen = mutableSetOf<Int>()
        val holidays = sortedMapOf<String, String>()
        fun invalid(): Nothing = throw ValidationException("Isi sumber kalender tidak lengkap atau tidak sesuai tahun")
        Jsoup.parse(html).select(".kalender-indo").forEach { block ->
            val title = block.selectFirst(".kal-title-link")?.text() ?: invalid()
            val month = names.indexOfFirst { title.equals("$it $year", true) } + 1
            if (month == 0 || !seen.add(month)) invalid()
            val entries = block.select(".kal-libur-list li")
            if (entries.isEmpty()) invalid()
            entries.forEach entry@{ item ->
                val day = item.selectFirst(".kal-libur-day")?.text().orEmpty().trim()
                if (day.isEmpty() && item.text().equals("Bulan Tanpa Libur Nasional", true)) return@entry
                if (!day.matches(Regex("""[0-9]{1,2}(?:\s*[-–]\s*[0-9]{1,2})?"""))) invalid()
                val range = day.split(Regex("""\s*[-–]\s*""")).map(String::toInt)
                val first = range.first(); val last = range.last()
                if (first < 1 || last < first || last > java.time.YearMonth.of(year, month).lengthOfMonth()) invalid()
                val copy = item.clone(); copy.select(".kal-libur-day").remove()
                val name = copy.text().trim().trim(':', '-', '–').trim()
                if (name.isBlank() || name.length > 300) invalid()
                for (date in first..last) {
                    val key = LocalDate.of(year, month, date).toString()
                    holidays[key] = holidays[key]?.let { if (name in it.split(" / ")) it else "$it / $name" } ?: name
                }
            }
        }
        if (seen.size != 12 || holidays.isEmpty()) invalid()
        return holidays
    }
}
