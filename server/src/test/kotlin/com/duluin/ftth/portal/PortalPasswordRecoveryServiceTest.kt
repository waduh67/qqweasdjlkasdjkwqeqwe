package com.duluin.ftth.portal

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.notification.TransactionalChannel
import com.duluin.ftth.portal.application.port.inbound.PortalResetPasswordCommand
import com.duluin.ftth.portal.application.service.PortalIdentityResolver
import com.duluin.ftth.portal.application.service.PortalIdentitySyncService
import com.duluin.ftth.portal.application.service.PortalPasswordRecoveryService
import com.duluin.ftth.portal.application.service.PortalTenantScopedRecovery
import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.portal.domain.model.PortalPasswordReset
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * Menguji alur "lupa password" dengan fake in-memory.
 *
 * Dua sifat yang paling dijaga di sini, karena keduanya keputusan keamanan yang mudah
 * hilang tanpa sadar saat kode dirapikan:
 *  - permintaan kode TIDAK PERNAH membedakan identitas dikenal dari yang tidak (tak melempar,
 *    tak pula punya efek samping yang bisa diamati dari luar);
 *  - kode terikat pada identitas yang diketik, sekali pakai, dan mati setelah beberapa kali
 *    salah — tiga batas yang menggantikan panjangnya yang cuma 6 digit.
 */
class PortalPasswordRecoveryServiceTest {

    private val tenantId = UUID.randomUUID()
    private val otherTenantId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()
    private val otherCustomerId = UUID.randomUUID()
    private val tenant = TenantRef(tenantId, "jayanet", "Jaya Net", TenantStatus.ACTIVE)
    private val otherTenant = TenantRef(otherTenantId, "sinarnet", "Sinar Net", TenantStatus.ACTIVE)

    private val credentials = InMemoryPortalCredentialRepository()
    private val refreshTokens = RecordingPortalRefreshTokenRepository()
    private val resets = InMemoryPortalPasswordResetRepository()
    private val hasher = PlainTextPortalPasswordHasher()
    private val directory = InMemoryPortalIdentityDirectory()
    private val notifications = RecordingNotificationApi()
    private val customers = StubCustomerApi(
        CustomerRef(customerId, "CUST-000007", "Budi", "0811222333", "budi@mail.com", Coordinate(0.0, 0.0), "ACTIVE"),
        CustomerRef(otherCustomerId, "CUST-000008", "Budi", "0811222333", "budi@mail.com", Coordinate(0.0, 0.0), "ACTIVE"),
    )
    private val tenants = StubTenantApi(tenant, otherTenant)
    private val auditEvents = mutableListOf<AuditTrailEvent>()
    private val publisher = ApplicationEventPublisher { event -> if (event is AuditTrailEvent) auditEvents.add(event) }

    private val identitySync = PortalIdentitySyncService(directory, credentials, customers)
    private val scoped = PortalTenantScopedRecovery(credentials, refreshTokens, resets, hasher, customers)
    private val service = PortalPasswordRecoveryService(
        PortalIdentityResolver(directory, tenants), tenants, resets, scoped, notifications, publisher,
    )

    @AfterEach
    fun clearTenant() = TenantContext.clear()

    private fun seedCredential(
        tenantId: UUID = this.tenantId,
        customerId: UUID = this.customerId,
        login: String = "budi01",
        password: String = "rahasia123",
        disabled: Boolean = false,
    ) {
        TenantContext.runAs(tenantId) {
            val credential = PortalCredential.create(customerId, login, hasher.hash(password))
            if (disabled) credential.disable()
            credentials.save(credential)
            identitySync.sync(customerId)
        }
    }

    /** Kode mentah tak pernah keluar dari service — satu-satunya jalannya lewat pesan terkirim. */
    private fun codeFromLastMessage(): String =
        Regex("""\b(\d{6})\b""").find(notifications.sent.last().body)!!.groupValues[1]

    @Test
    fun `minta kode lewat email mengirim ke email terdaftar`() {
        seedCredential()

        service.requestReset("budi@mail.com")

        assertThat(notifications.sent).hasSize(1)
        val message = notifications.sent.single()
        assertThat(message.channel).isEqualTo(TransactionalChannel.EMAIL)
        assertThat(message.destination).isEqualTo("budi@mail.com")
        assertThat(message.body).contains("Jaya Net")
        assertThat(auditEvents.map { it.action }).containsExactly("portal.credential.reset-requested")
    }

    @Test
    fun `minta kode lewat nomor HP mengirim ke WhatsApp dengan nomor ternormalisasi`() {
        seedCredential()

        service.requestReset("0811-222-333")

        val message = notifications.sent.single()
        assertThat(message.channel).isEqualTo(TransactionalChannel.WHATSAPP)
        assertThat(message.destination).isEqualTo("62811222333")
    }

    @Test
    fun `minta kode lewat username jatuh ke email karena username tak menunjuk kanal`() {
        seedCredential()

        service.requestReset("budi01")

        assertThat(notifications.sent.single().channel).isEqualTo(TransactionalChannel.EMAIL)
    }

    @Test
    fun `kode maupun audit tidak pernah memuat isi pesan`() {
        seedCredential()

        service.requestReset("budi@mail.com")

        val code = codeFromLastMessage()
        // Yang tersimpan hanya hash-nya, dan audit cuma mencatat peristiwanya.
        assertThat(resets.all().single().codeHash).isNotEqualTo(code)
        assertThat(auditEvents.single().detail.values.map { it.toString() }).noneMatch { it.contains(code) }
    }

    @Test
    fun `identitas tak dikenal tak melempar dan tak mengirim apa pun`() {
        seedCredential()

        assertThatCode { service.requestReset("orang@lain.com") }.doesNotThrowAnyException()

        assertThat(notifications.sent).isEmpty()
        assertThat(resets.all()).isEmpty()
    }

    @Test
    fun `slug tak dikenal diperlakukan seperti tanpa slug, bukan error`() {
        seedCredential()

        assertThatCode { service.requestReset("budi@mail.com", "tidakada") }.doesNotThrowAnyException()

        assertThat(notifications.sent).hasSize(1)
    }

    @Test
    fun `pelanggan tanpa kredensial portal tak dikirimi kode`() {
        // Terindeks (mis. sisa akun lama) tapi kredensialnya sudah tak ada.
        seedCredential()
        TenantContext.runAs(tenantId) { credentials.deleteFor(customerId) }

        service.requestReset("budi@mail.com")

        assertThat(notifications.sent).isEmpty()
    }

    @Test
    fun `akun yang dinonaktifkan operator tak dikirimi kode`() {
        seedCredential(disabled = true)

        service.requestReset("budi@mail.com")

        assertThat(notifications.sent).isEmpty()
    }

    @Test
    fun `permintaan beruntun ditahan jeda kirim-ulang`() {
        seedCredential()

        service.requestReset("budi@mail.com")
        service.requestReset("budi@mail.com")

        assertThat(notifications.sent).hasSize(1)
    }

    @Test
    fun `kode baru mematikan kode sebelumnya`() {
        seedCredential()
        service.requestReset("budi@mail.com")
        val first = codeFromLastMessage()
        resets.agePast(PortalPasswordReset.RESEND_COOLDOWN)

        service.requestReset("budi@mail.com")

        assertThatThrownBy {
            service.completeReset(PortalResetPasswordCommand("budi@mail.com", first, "passwordbaru1"))
        }.isInstanceOf(ValidationException::class.java)
        // Yang terbaru tetap sah.
        service.completeReset(PortalResetPasswordCommand("budi@mail.com", codeFromLastMessage(), "passwordbaru1"))
    }

    @Test
    fun `identitas yang dipakai di dua ISP menerima kode untuk masing-masing`() {
        seedCredential()
        seedCredential(tenantId = otherTenantId, customerId = otherCustomerId, login = "budi02")

        service.requestReset("budi@mail.com")

        assertThat(notifications.sent).hasSize(2)
        // Nama ISP disebut supaya pelanggan tahu kode mana untuk akun yang mana.
        assertThat(notifications.sent.map { it.body }).anyMatch { it.contains("Jaya Net") }
        assertThat(notifications.sent.map { it.body }).anyMatch { it.contains("Sinar Net") }
    }

    @Test
    fun `tukar kode memasang password baru dan mencabut seluruh sesi`() {
        seedCredential()
        service.requestReset("budi@mail.com")
        auditEvents.clear()

        service.completeReset(PortalResetPasswordCommand("budi@mail.com", codeFromLastMessage(), "passwordbaru1"))

        val stored = TenantContext.runAs(tenantId) { credentials.findByCustomerId(customerId)!! }
        assertThat(hasher.matches("passwordbaru1", stored.passwordHash)).isTrue()
        assertThat(refreshTokens.revokedCustomers).contains(customerId)
        assertThat(auditEvents.map { it.action }).containsExactly("portal.credential.recovered")
    }

    @Test
    fun `kode hanya bisa dipakai sekali`() {
        seedCredential()
        service.requestReset("budi@mail.com")
        val code = codeFromLastMessage()
        service.completeReset(PortalResetPasswordCommand("budi@mail.com", code, "passwordbaru1"))

        assertThatThrownBy {
            service.completeReset(PortalResetPasswordCommand("budi@mail.com", code, "passwordlain9"))
        }.isInstanceOf(ValidationException::class.java)
            .hasMessage("Kode pemulihan tidak valid atau sudah kedaluwarsa")
    }

    @Test
    fun `kode ditolak bila ditukar atas nama identitas lain`() {
        seedCredential()
        service.requestReset("budi@mail.com")

        assertThatThrownBy {
            service.completeReset(PortalResetPasswordCommand("budi01", codeFromLastMessage(), "passwordbaru1"))
        }.isInstanceOf(ValidationException::class.java)

        // Percobaan salah itu ikut menggerus jatah tebakan.
        assertThat(resets.all().single().attempts).isEqualTo(1)
    }

    @Test
    fun `kode mati setelah percobaan salah beruntun`() {
        seedCredential()
        service.requestReset("budi@mail.com")
        val code = codeFromLastMessage()
        repeat(PortalPasswordReset.MAX_ATTEMPTS) {
            runCatching {
                service.completeReset(PortalResetPasswordCommand("budi01", code, "passwordbaru1"))
            }
        }

        assertThatThrownBy {
            service.completeReset(PortalResetPasswordCommand("budi@mail.com", code, "passwordbaru1"))
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `kode salah ditolak dengan pesan yang sama seperti kode kedaluwarsa`() {
        seedCredential()
        service.requestReset("budi@mail.com")

        assertThatThrownBy {
            service.completeReset(PortalResetPasswordCommand("budi@mail.com", "000000", "passwordbaru1"))
        }.isInstanceOf(ValidationException::class.java)
            .hasMessage("Kode pemulihan tidak valid atau sudah kedaluwarsa")
    }

    @Test
    fun `password baru yang terlalu pendek ditolak dan kode tetap utuh`() {
        seedCredential()
        service.requestReset("budi@mail.com")
        val code = codeFromLastMessage()

        assertThatThrownBy {
            service.completeReset(PortalResetPasswordCommand("budi@mail.com", code, "pendek"))
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("minimal 8 karakter")

        // Salah ketik panjang password bukan tebakan kode — kodenya tak boleh ikut hangus.
        service.completeReset(PortalResetPasswordCommand("budi@mail.com", code, "passwordbaru1"))
    }
}
