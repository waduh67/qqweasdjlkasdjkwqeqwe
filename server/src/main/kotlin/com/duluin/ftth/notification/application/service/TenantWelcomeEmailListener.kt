package com.duluin.ftth.notification.application.service

import com.duluin.ftth.iam.TenantAdminProvisionedEvent
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Email selamat datang untuk ISP yang baru terdaftar — isi utamanya KODE ISP, karena kode itu
 * kini dipilih server dan tak pernah diketik pendaftar, sementara layar masuk memintanya setiap
 * kali. Layar sukses pendaftaran memang menampilkannya juga, tapi layar itu hilang begitu tab
 * ditutup; email tidak.
 *
 * Mendengarkan [TenantAdminProvisionedEvent] alih-alih dipanggil iam lewat `NotificationApi`:
 * arah `iam → notification` menutup siklus modul (alasan lengkapnya di KDoc event itu).
 *
 * Memakai merek PLATFORM, bukan merek tenant. Ini surat dari penyedia SaaS kepada ISP barunya,
 * dan pada detik ia berangkat tenant penerimanya memang belum punya logo, warna, maupun alamat
 * pengirim sendiri — [EmailBrandingResolver.platformOnly] juga sengaja tak menyentuh repo
 * ber-RLS, yang tepat karena listener ini berjalan tanpa tenant context.
 */
@Component
class TenantWelcomeEmailListener(
    private val branding: EmailBrandingResolver,
    private val subjects: EmailSubjectResolver,
    private val renderer: EmailRenderer,
    private val dispatcher: EmailDispatcher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * AFTER_COMMIT dengan `fallbackExecution = true` (pola `TenantOnboardedListener`): surat
     * baru berangkat setelah tenant benar-benar tersimpan, tapi onboarding tak selalu dibungkus
     * transaksi sehingga tanpa fallback listener ini diam saja di jalur `/signup`.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: TenantAdminProvisionedEvent) {
        // Relay SMTP yang mati tak boleh membatalkan pendaftaran yang sudah commit — tenant
        // dan adminnya sudah ada, dan kodenya masih terbaca di layar sukses.
        runCatching {
            val identity = branding.platformOnly()
            val outcome = dispatcher.send(
                renderer.render(
                    to = event.adminEmail,
                    subject = subjects.forPlatform(NotificationTrigger.TENANT_SIGNED_UP, event.tenantName),
                    body = composeBody(event),
                    identity = identity,
                ),
            )
            log.info(
                "Email selamat datang tenant {} ke {}: {}",
                event.tenantSlug,
                event.adminEmail,
                outcome.detail ?: outcome.status.name,
            )
        }.onFailure {
            log.warn("Gagal mengirim email selamat datang untuk tenant {}", event.tenantSlug, it)
        }
    }

    /**
     * Kode ISP diberi barisnya sendiri supaya mudah disalin dan tak tenggelam di tengah
     * kalimat. Password tak pernah disebut ulang: yang mengetiknya sudah tahu, dan email adalah
     * tempat terakhir yang pantas menyimpannya.
     */
    private fun composeBody(event: TenantAdminProvisionedEvent): String = buildString {
        append("Halo ${event.adminName},\n\n")
        append("Pendaftaran ${event.tenantName} berhasil. Konsol Anda sudah siap dipakai.\n\n")
        append("Kode ISP: ${event.tenantSlug}\n\n")
        append(
            "Simpan kode di atas. Ia diminta bersama email dan password setiap kali Anda — atau " +
                "staf yang Anda undang — masuk ke konsol.\n\n",
        )
        append("Email admin: ${event.adminEmail}\n")
        // Tautan dilewati bila alamat aplikasi belum disetel di mana pun; email tanpa tautan
        // masih berguna, email dengan tautan setengah jadi tidak.
        branding.publicBaseUrl()?.let { append("Masuk di: $it/login\n") }
    }
}
