package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.infrastructure.config.ObservabilityProperties
import com.duluin.ftth.common.infrastructure.observability.JobHealth
import com.duluin.ftth.common.infrastructure.observability.ScheduledJobRecovered
import com.duluin.ftth.common.infrastructure.observability.ScheduledJobStalled
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Mengabarkan pekerjaan latar yang macet (dan yang pulih) ke operator platform lewat email.
 *
 * Ini SATU-SATUNYA pemberitahuan di modul ini yang tak ditujukan kepada pelanggan dan tak
 * terikat tenant mana pun: penerimanya adalah orang yang mengurus server, dan kejadiannya
 * menimpa seluruh tenant sekaligus. Karena itu ia lewat [EmailDispatcher] platform langsung,
 * bukan lewat `NotificationSender` yang menghormati saklar pemicu tiap tenant — tak masuk
 * akal bila ISP pelanggan kita bisa mematikan peringatan tentang server kita sendiri.
 *
 * Penjaganya ([com.duluin.ftth.common.infrastructure.observability.JobStallWatchdog]) tinggal
 * di `common` dan cuma menerbitkan peristiwa; pengiriman email ada di sini, tempat SMTP
 * memang berumah. Batas modul tetap utuh: `common` tak pernah tahu email itu ada.
 */
@Component
class JobHealthAlertListener(
    private val email: EmailDispatcher,
    private val properties: ObservabilityProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun on(event: ScheduledJobStalled) {
        val to = recipient() ?: return
        val headline = if (event.repeated) "MASIH MACET" else "MACET"
        deliver(to, "[$BRAND] $headline: ${event.job.name}", stallBody(event.job, event.repeated))
    }

    @EventListener
    fun on(event: ScheduledJobRecovered) {
        val to = recipient() ?: return
        deliver(to, "[$BRAND] Pulih: ${event.job.name}", recoveryBody(event.job))
    }

    /** Null = tak ada penerima; penjaga sudah menulis ke log, jadi tak perlu berisik lagi. */
    private fun recipient(): String? = properties.alertEmail.trim().takeIf { it.isNotEmpty() }

    private fun deliver(to: String, subject: String, body: String) {
        // Kegagalan kirim TIDAK dilempar: peristiwanya terbit dari utas penjaga, dan penjaga
        // yang mati gara-gara SMTP mati adalah cara paling konyol kehilangan pemantauan.
        val outcome = runCatching { email.send(to, subject, body, BRAND) }.getOrElse { failure ->
            log.warn("Peringatan job gagal dikirim ke {}: {}", to, failure.message, failure)
            return
        }
        if (outcome.status == DeliveryStatus.FAILED) {
            log.warn("Peringatan job gagal dikirim ke {}: {}", to, outcome.detail)
        }
    }

    private fun stallBody(job: JobHealth, repeated: Boolean): String = buildString {
        appendLine("Sebuah pekerjaan latar $BRAND berhenti berhasil diselesaikan.")
        appendLine()
        appendLine("  Job             : ${job.name} (modul ${job.module})")
        appendLine("  Jadwal          : ${job.interval?.let { "tiap ${humanize(it)}" } ?: "tidak tetap"}")
        appendLine("  Sukses terakhir : ${job.lastSuccessAt?.toString() ?: "belum pernah sejak server hidup"}")
        appendLine("  Sudah lewat     : ${humanize(job.sinceSuccess)}")
        appendLine("  Ambang macet    : ${job.stallAfter?.let { humanize(it) } ?: "-"}")
        appendLine("  Ronde tercatat  : ${job.runs} jalan, ${job.failures} gagal")
        appendLine("  Galat terakhir  : ${job.lastError ?: "tidak ada — job tampaknya berhenti dijadwalkan"}")
        appendLine()
        if (repeated) {
            appendLine("Ini pengingat: kemacetan yang sama masih berlangsung sejak peringatan sebelumnya.")
        }
        appendLine(
            "Selama macet, pekerjaan ini tidak menghasilkan apa pun dan tidak ada layar yang " +
                "berubah karenanya — akibatnya baru terlihat sebagai keluhan pelanggan berhari-hari " +
                "kemudian. Periksa log server pada rentang waktu di atas.",
        )
    }

    private fun recoveryBody(job: JobHealth): String = buildString {
        appendLine("Pekerjaan latar ${job.name} (modul ${job.module}) kembali berhasil.")
        appendLine()
        appendLine("  Sukses terakhir : ${job.lastSuccessAt?.toString() ?: "-"}")
        appendLine("  Ronde tercatat  : ${job.runs} jalan, ${job.failures} gagal")
        appendLine()
        appendLine("Tak ada tindakan yang diperlukan. Pekerjaan yang tertinggal selama macet tidak")
        appendLine("dijalankan ulang otomatis — periksa hasilnya bila rentangnya panjang.")
    }

    private companion object {
        const val BRAND = "NetOps Console"

        /**
         * Durasi dalam bahasa manusia, dua satuan terbesar saja: "2 jam 15 menit" jauh lebih
         * cepat dipahami orang yang baru bangun daripada "PT2H15M32S".
         */
        fun humanize(duration: Duration): String {
            val total = duration.seconds.coerceAtLeast(0)
            if (total < 60) return "$total detik"
            val parts = mutableListOf<String>()
            var rest = total
            for ((seconds, label) in listOf(86_400L to "hari", 3_600L to "jam", 60L to "menit")) {
                val count = rest / seconds
                if (count > 0) {
                    parts += "$count $label"
                    rest %= seconds
                }
                if (parts.size == 2) break
            }
            return parts.joinToString(" ")
        }
    }
}
