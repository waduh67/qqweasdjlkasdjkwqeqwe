package com.duluin.ftth.helpdesk.application.service

import com.duluin.ftth.helpdesk.HelpdeskReportApi
import com.duluin.ftth.helpdesk.HelpdeskSupportReport
import com.duluin.ftth.helpdesk.application.port.outbound.TicketRepository
import com.duluin.ftth.helpdesk.domain.model.Ticket
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Implementasi [HelpdeskReportApi]: menghitung kinerja meja bantuan dari tiket yang tersimpan.
 * Murni baca; tak menyentuh agregat lain. Dua himpunan diambil terpisah — yang MASUK di rentang
 * dan yang TUNTAS di rentang — karena satu tiket boleh melewati batas periode.
 */
@Service
@Transactional(readOnly = true)
class HelpdeskReportService(
    private val tickets: TicketRepository,
) : HelpdeskReportApi {

    override fun supportReport(from: LocalDate, to: LocalDate): HelpdeskSupportReport {
        val fromInstant = from.atStartOfDay(zone).toInstant()
        val toExclusive = to.plusDays(1).atStartOfDay(zone).toInstant()

        val opened = tickets.findOpenedBetween(fromInstant, toExclusive)
        val resolved = tickets.findResolvedBetween(fromInstant, toExclusive)
        val now = Instant.now()

        // Telat menjawab = jawaban pertama melewati tenggat, ATAU belum dijawab sama sekali padahal
        // tenggatnya sudah lewat. Yang belum dijawab tapi tenggatnya belum tiba masih punya harapan.
        val responseBreached = opened.count { t ->
            val due = t.responseDueAt ?: return@count false
            t.firstResponseAt?.isAfter(due) ?: due.isBefore(now)
        }
        val resolutionBreached = resolved.count { it.resolvedAt!!.isAfter(it.resolutionDueAt) }

        val compliance = if (resolved.isNotEmpty()) {
            BigDecimal(resolved.size - resolutionBreached)
                .multiply(HUNDRED)
                .divide(BigDecimal(resolved.size), 2, RoundingMode.HALF_UP)
        } else {
            null
        }

        return HelpdeskSupportReport(
            openedCount = opened.size,
            resolvedCount = resolved.size,
            openedByCategory = opened.groupingBy { it.category.name }.eachCount(),
            avgFirstResponseHours = opened.avgHours { it.openedAt to it.firstResponseAt },
            avgResolutionHours = resolved.avgHours { it.openedAt to it.resolvedAt },
            responseBreachedCount = responseBreached,
            resolutionBreachedCount = resolutionBreached,
            slaCompliancePercent = compliance,
        )
    }

    /**
     * Rata-rata jam antara dua titik waktu sebuah tiket; baris yang titik akhirnya belum ada
     * dilewati, dan `null` bila tak satu pun bisa dihitung — "belum ada yang dijawab" bukan
     * "dijawab dalam 0 jam".
     */
    private fun List<Ticket>.avgHours(span: (Ticket) -> Pair<Instant, Instant?>): Double? {
        val durations = mapNotNull { ticket ->
            val (start, end) = span(ticket)
            end?.takeIf { !it.isBefore(start) }?.let { Duration.between(start, it).toMillis() }
        }
        if (durations.isEmpty()) return null
        return durations.average() / MILLIS_PER_HOUR
    }

    private companion object {
        /** Batas hari→instant memakai zona server, selaras dengan penjadwal SLA. */
        val zone: ZoneId = ZoneId.systemDefault()
        val HUNDRED: BigDecimal = BigDecimal(100)
        const val MILLIS_PER_HOUR = 3_600_000.0
    }
}
