package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.EmailSubjectRepository
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.tenancy.TenantApi
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
 * Dua hal yang tak berlaku merata untuk semua pemicu:
 *
 *  - [PLATFORM_ONLY] — pemicu yang timpaan tenantnya DIABAIKAN (lihat KDoc-nya).
 *  - [ISP_TOKEN] — penanda `{isp}` di dalam subjek yang diganti nama ISP saat dikirim,
 *    sehingga satu subjek global tetap terbaca personal di kotak masuk pelanggan.
 *
 * [DEFAULT_SUBJECTS] sengaja publik: UI menampilkannya sebagai placeholder supaya operator
 * melihat apa yang akan terkirim bila kolomnya dibiarkan kosong, alih-alih menebak.
 */
@Component
class EmailSubjectResolver(
    private val subjects: EmailSubjectRepository,
    private val tenantApi: TenantApi,
) {
    /**
     * Subjek yang berlaku untuk tenant aktif, `{isp}` sudah diganti nama tenant. Dibaca dalam
     * konteks tenant (RLS menyaring timpaannya).
     */
    @Transactional(readOnly = true)
    fun forCurrentTenant(trigger: NotificationTrigger): String {
        val tenantId = TenantContext.tenantId()
        val raw = subjects.tenantSubjects()[trigger]?.takeUnless { trigger in PLATFORM_ONLY }
            ?: subjects.platformSubjects()[trigger]
            ?: DEFAULT_SUBJECTS.getValue(trigger)
        return expand(raw, tenantApi.findById(tenantId)?.name.orEmpty())
    }

    /**
     * Subjek level platform tanpa menimbang timpaan tenant — dipakai layar platform,
     * pratinjaunya, dan jalur yang belum/tak punya tenant aktif (mis. email selamat datang
     * pendaftaran, yang tenant-nya baru saja lahir dan belum punya setelan apa pun).
     *
     * [ispName] boleh kosong; `{isp}` lalu hilang dari subjek alih-alih tampil mentah.
     */
    @Transactional(readOnly = true)
    fun forPlatform(trigger: NotificationTrigger, ispName: String = ""): String =
        expand(subjects.platformSubjects()[trigger] ?: DEFAULT_SUBJECTS.getValue(trigger), ispName)

    /**
     * Peta LENGKAP subjek yang berlaku untuk tenant aktif — sekali baca untuk seluruh pemicu,
     * agar layar setelan tak menembak repo sembilan kali. Token `{isp}` sengaja TIDAK diganti:
     * ini yang ditampilkan di kolom setelan, dan operator perlu melihat tokennya apa adanya.
     */
    @Transactional(readOnly = true)
    fun effectiveForCurrentTenant(): Map<NotificationTrigger, String> {
        val platform = subjects.platformSubjects()
        val tenant = subjects.tenantSubjects()
        return DEFAULT_SUBJECTS.mapValues { (trigger, fallback) ->
            tenant[trigger]?.takeUnless { trigger in PLATFORM_ONLY } ?: platform[trigger] ?: fallback
        }
    }

    /** Peta lengkap subjek level platform (bawaan kode ditimpa baris platform). */
    @Transactional(readOnly = true)
    fun effectiveForPlatform(): Map<NotificationTrigger, String> {
        val platform = subjects.platformSubjects()
        return DEFAULT_SUBJECTS.mapValues { (trigger, fallback) -> platform[trigger] ?: fallback }
    }

    private fun expand(subject: String, ispName: String): String = subject.replace(ISP_TOKEN, ispName)

    companion object {
        /** Penanda nama ISP di dalam subjek; satu-satunya token yang dikenali. */
        const val ISP_TOKEN = "{isp}"

        /**
         * Pemicu milik PLATFORM: timpaan tenant diabaikan dan barisnya tak ditawarkan di layar
         * setelan tenant.
         *
         * Keduanya bukan "pemberitahuan dari ISP kepada pelanggannya" melainkan surat dari
         * mekanisme aplikasi itu sendiri. `PORTAL_PASSWORD_RESET` adalah bagian dari jalan
         * masuk pelanggan — ISP yang mengarang subjeknya sendiri bisa membuatnya tak terbaca
         * sebagai email keamanan. `TENANT_SIGNED_UP` bahkan belum punya tenant pemilik saat
         * dikirim: ia berangkat atas nama penyedia SaaS.
         */
        val PLATFORM_ONLY: Set<NotificationTrigger> = setOf(
            NotificationTrigger.PORTAL_PASSWORD_RESET,
            NotificationTrigger.TENANT_SIGNED_UP,
        )

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
            // Nama ISP disebut karena pelanggan bisa memegang akun di beberapa ISP sekaligus;
            // tanpa itu, dua email pemulihan tampak identik di kotak masuk.
            NotificationTrigger.PORTAL_PASSWORD_RESET to "Kode pemulihan akun {isp}",
            NotificationTrigger.TENANT_SIGNED_UP to "Pendaftaran {isp} berhasil — kode ISP Anda",
        )
    }
}
