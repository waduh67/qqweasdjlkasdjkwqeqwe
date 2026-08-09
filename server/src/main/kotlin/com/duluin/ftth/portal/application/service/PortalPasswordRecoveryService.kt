package com.duluin.ftth.portal.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.NotificationApi
import com.duluin.ftth.notification.TransactionalChannel
import com.duluin.ftth.notification.TransactionalMessage
import com.duluin.ftth.notification.TransactionalPurpose
import com.duluin.ftth.portal.application.port.inbound.PortalPasswordRecoveryUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalResetPasswordCommand
import com.duluin.ftth.portal.application.port.outbound.PortalPasswordResetRepository
import com.duluin.ftth.portal.domain.model.PortalIdentifier
import com.duluin.ftth.portal.domain.model.PortalPasswordReset
import com.duluin.ftth.portal.domain.model.PortalResetChannel
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Orkestrasi pemulihan password portal. Seperti [PortalAuthenticationService] dan untuk alasan
 * yang sama, SENGAJA tidak `@Transactional`: satu permintaan bisa menyentuh beberapa ISP, dan
 * tiap pekerjaan ber-RLS harus dibuka di dalam [TenantContext]-nya sendiri. Kerja
 * transaksionalnya ada di [PortalTenantScopedRecovery]; di sini tinggal urusan lintas-tenant,
 * pengiriman pesan, dan audit.
 *
 * Tiga keputusan yang membentuk kelas ini:
 *
 *  1. **Permintaan kode selalu "berhasil".** Tak ada jawaban yang membedakan identitas dikenal
 *     dari yang tidak — juga tidak dalam bentuk tersamar. Halaman ini terbuka untuk umum;
 *     jawaban yang membedakan menjadikannya alat memetakan basis pelanggan sebuah ISP.
 *  2. **Kodenya TIDAK lewat riwayat broadcast.** Riwayat itu memang dibuat untuk dibaca
 *     operator, jadi menuliskan kode ke sana sama dengan menyerahkan kunci akun pelanggan ke
 *     seluruh staf ISP. Karena itu jalurnya [NotificationApi.sendTransactional], dan yang
 *     tercatat hanya peristiwanya — siapa, kanal apa — tanpa isi.
 *  3. **Satu identitas bisa menyentuh beberapa ISP.** Bila seseorang berlangganan di dua
 *     tempat dengan nomor sama, kode terbit untuk masing-masing dan pesannya menyebut nama
 *     ISP-nya, sehingga jelas kode mana untuk akun yang mana.
 */
@Service
class PortalPasswordRecoveryService(
    private val identityResolver: PortalIdentityResolver,
    private val tenantApi: TenantApi,
    private val resets: PortalPasswordResetRepository,
    private val scoped: PortalTenantScopedRecovery,
    private val notifications: NotificationApi,
    private val events: ApplicationEventPublisher,
) : PortalPasswordRecoveryUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun requestReset(identifier: String, tenantSlug: String?) {
        val trimmed = identifier.trim()
        if (trimmed.isEmpty()) return
        // Slug tak dikenal / tenant tak aktif diperlakukan seperti "tanpa slug" alih-alih
        // dilempar: permintaan ini tak boleh punya jawaban yang membedakan apa pun.
        val requestedTenant = tenantSlug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { tenantApi.findBySlug(it) }
            ?.takeIf { it.status == TenantStatus.ACTIVE }

        identityResolver.resolve(trimmed, requestedTenant, MAX_TARGETS).forEach { candidate ->
            // Satu ISP gagal tak boleh menghentikan yang lain, dan tak boleh pula terlihat dari
            // luar — pemanggil tetap menerima jawaban yang sama persis.
            runCatching {
                TenantContext.runAs(candidate.tenant.id) {
                    scoped.issueCode(candidate.customerId, trimmed)?.let { deliver(candidate.tenant, it) }
                }
            }.onFailure {
                log.warn("Gagal menerbitkan kode pemulihan portal untuk tenant {}", candidate.tenant.id, it)
            }
        }
    }

    override fun completeReset(command: PortalResetPasswordCommand) {
        val reset = resets.findByCodeHash(PortalTokens.sha256(command.code.trim())) ?: throw invalidCode()
        if (!reset.isUsable()) throw invalidCode()

        // Kode HARUS ditukar dengan identitas yang sama seperti saat diminta. Tanpa ikatan ini,
        // kode yang terbaca orang lain (mis. notifikasi muncul di layar terkunci) bisa dipakai
        // atas nama akun mana pun yang kodenya kebetulan ia pegang.
        if (reset.identifier !in PortalIdentifier.candidates(command.identifier)) {
            TenantContext.runAs(reset.tenantId) { scoped.recordFailedAttempt(reset) }
            throw invalidCode()
        }
        validatePassword(command.newPassword)

        TenantContext.runAs(reset.tenantId) { scoped.completeReset(reset, command.newPassword) }
        audit("portal.credential.recovered", reset.tenantId, reset.customerId, mapOf("via" to reset.channel.name))
    }

    /** Kirim kode yang sudah terbit, lalu catat peristiwanya (tanpa isi pesan). */
    private fun deliver(tenant: TenantRef, issued: IssuedResetCode) {
        val delivery = notifications.sendTransactional(
            TransactionalMessage(
                purpose = TransactionalPurpose.PORTAL_PASSWORD_RESET,
                channel = issued.channel.toTransactional(),
                destination = issued.destination,
                recipientName = issued.recipientName,
                subject = "Kode pemulihan akun ${tenant.name}",
                body = composeBody(tenant, issued),
            ),
        )
        audit(
            "portal.credential.reset-requested",
            tenant.id,
            issued.customerId,
            mapOf("via" to issued.channel.name, "terkirim" to delivery.delivered),
        )
    }

    /**
     * Nama ISP disebut karena pelanggan bisa memegang beberapa akun; peringatan penutup ada
     * karena penipuan paling umum di jalur ini adalah menelepon pelanggan sambil mengaku
     * petugas dan meminta kodenya dibacakan.
     */
    private fun composeBody(tenant: TenantRef, issued: IssuedResetCode): String =
        "Kode pemulihan akun ${tenant.name} (${issued.login}): ${issued.code}. " +
            "Berlaku ${PortalPasswordReset.TTL.toMinutes()} menit dan hanya bisa dipakai sekali. " +
            "Jangan berikan kode ini kepada siapa pun, termasuk yang mengaku petugas."

    private fun PortalResetChannel.toTransactional(): TransactionalChannel = when (this) {
        PortalResetChannel.EMAIL -> TransactionalChannel.EMAIL
        PortalResetChannel.WHATSAPP -> TransactionalChannel.WHATSAPP
    }

    private fun audit(action: String, tenantId: UUID, customerId: UUID, detail: Map<String, Any?>) {
        events.publishEvent(
            AuditTrailEvent(
                tenantId = tenantId,
                actorId = customerId,
                actorEmail = null,
                action = action,
                entityType = "PortalCredential",
                entityId = customerId.toString(),
                detail = detail,
            ),
        )
    }

    private fun validatePassword(raw: String) {
        if (raw.length < MIN_PASSWORD_LENGTH) {
            throw ValidationException("Password portal minimal $MIN_PASSWORD_LENGTH karakter")
        }
    }

    /**
     * Satu pesan untuk semua sebab kegagalan penukaran (kode salah, kedaluwarsa, habis
     * percobaan, identitas tak cocok). Membedakannya akan memberi tahu penebak seberapa dekat
     * ia — dan hanya itu yang ia butuhkan.
     */
    private fun invalidCode() = ValidationException("Kode pemulihan tidak valid atau sudah kedaluwarsa")

    private companion object {
        /** Sama dengan aturan panjang password di jalur provisioning operator. */
        const val MIN_PASSWORD_LENGTH = 8

        /**
         * Berapa ISP sekaligus yang boleh dikirimi kode untuk satu permintaan. Menahan jalur
         * ini dipakai membanjiri WhatsApp/email orang lain lewat identitas yang dipakai di
         * banyak tempat.
         */
        const val MAX_TARGETS = 3
    }
}
