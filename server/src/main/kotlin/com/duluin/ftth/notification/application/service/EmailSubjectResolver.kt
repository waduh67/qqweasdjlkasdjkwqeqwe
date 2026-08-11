package com.duluin.ftth.notification.application.service

import com.duluin.ftth.notification.application.port.outbound.EmailSubjectRepository
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Baris subjek email per pemicu, dengan tiga tingkat pewarisan: timpaan TENANT → timpaan
 * PLATFORM → konstanta di kode.
 *
 * Hanya subjek yang perlu dijahit di sini; isi pesannya sudah dirangkai listener yang
 * menerbitkan peristiwanya dan dipakai apa adanya di badan email. WhatsApp tak mengenal
 * padanan subjek, jadi ini memang urusan kanal email saja.
 *
 * [DEFAULT_SUBJECTS] sengaja publik: UI menampilkannya sebagai placeholder supaya operator
 * melihat apa yang akan terkirim bila kolomnya dibiarkan kosong, alih-alih menebak.
 */
@Component
class EmailSubjectResolver(
    private val subjects: EmailSubjectRepository,
) {
    /** Subjek yang berlaku untuk tenant aktif. Dibaca dalam konteks tenant (RLS menyaringnya). */
    @Transactional(readOnly = true)
    fun forCurrentTenant(trigger: NotificationTrigger): String =
        subjects.tenantSubjects()[trigger]
            ?: subjects.platformSubjects()[trigger]
            ?: DEFAULT_SUBJECTS.getValue(trigger)

    /** Subjek level platform (tanpa menimbang tenant) — dipakai layar platform & pratinjaunya. */
    @Transactional(readOnly = true)
    fun forPlatform(trigger: NotificationTrigger): String =
        subjects.platformSubjects()[trigger] ?: DEFAULT_SUBJECTS.getValue(trigger)

    /**
     * Peta LENGKAP subjek yang berlaku untuk tenant aktif — sekali baca untuk seluruh pemicu,
     * agar layar setelan tak menembak repo delapan kali.
     */
    @Transactional(readOnly = true)
    fun effectiveForCurrentTenant(): Map<NotificationTrigger, String> {
        val platform = subjects.platformSubjects()
        val tenant = subjects.tenantSubjects()
        return DEFAULT_SUBJECTS.mapValues { (trigger, fallback) ->
            tenant[trigger] ?: platform[trigger] ?: fallback
        }
    }

    /** Peta lengkap subjek level platform (bawaan kode ditimpa baris platform). */
    @Transactional(readOnly = true)
    fun effectiveForPlatform(): Map<NotificationTrigger, String> {
        val platform = subjects.platformSubjects()
        return DEFAULT_SUBJECTS.mapValues { (trigger, fallback) -> platform[trigger] ?: fallback }
    }

    companion object {
        /**
         * Subjek bawaan, dulu dipaku di `NotificationSender.subjectFor`. Peta lengkap (semua
         * pemicu punya entri) supaya menambah pemicu baru gagal keras di test alih-alih
         * diam-diam mengirim email tanpa subjek.
         */
        val DEFAULT_SUBJECTS: Map<NotificationTrigger, String> = mapOf(
            NotificationTrigger.SUBSCRIPTION_ACTIVATED to "Layanan internet Anda sudah aktif",
            NotificationTrigger.SUBSCRIPTION_ISOLATED to "Layanan internet Anda dinonaktifkan sementara",
            NotificationTrigger.SUBSCRIPTION_TERMINATED to "Layanan internet Anda telah dihentikan",
            NotificationTrigger.INVOICE_DUE_SOON to "Tagihan internet Anda akan jatuh tempo",
            NotificationTrigger.INVOICE_OVERDUE to "Tagihan internet Anda telah melewati jatuh tempo",
            NotificationTrigger.WORK_ORDER_SCHEDULED to "Jadwal kunjungan teknisi",
            NotificationTrigger.INCIDENT_OPENED to "Pemberitahuan gangguan layanan",
            NotificationTrigger.MANUAL to "Pemberitahuan dari penyedia layanan internet Anda",
            NotificationTrigger.PORTAL_PASSWORD_RESET to "Kode pemulihan password",
        )
    }
}
