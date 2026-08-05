package com.duluin.ftth.iam

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantResult
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.iam.application.port.inbound.SelfSignupCommand
import com.duluin.ftth.iam.application.port.outbound.UserDirectory
import com.duluin.ftth.iam.application.service.SelfSignupService
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pendaftaran mandiri harus MENOLAK slug/email yang sudah dipakai (beda dari onboarding
 * admin yang idempotent) — kalau tidak, slug kembar menyuntik admin baru ke tenant orang
 * lain (pengambilalihan). Cek keunikan juga harus bekerja pada nilai yang DINORMALKAN
 * (trim + lowercase), bukan mentah.
 */
class SelfSignupServiceTest {

    private val takenSlugs = mutableSetOf<String>()
    private val takenEmails = mutableSetOf<String>()
    private val slugLookups = mutableListOf<String>()
    private val emailLookups = mutableListOf<String>()
    private val onboard = RecordingOnboard()

    private val service = SelfSignupService(StubTenantApi(), StubUserDirectory(), onboard)

    @Test
    fun `pendaftaran baru memanggil onboard dengan nilai ternormalkan`() {
        val result = service.signup(
            SelfSignupCommand(
                slug = "  NetMedia ",
                name = "  Net Media  ",
                adminEmail = "  Budi@Net.ID ",
                adminName = "  Budi  ",
                adminPassword = "rahasia123",
            ),
        )

        val cmd = onboard.commands.single()
        assertThat(cmd.slug).isEqualTo("netmedia")
        assertThat(cmd.name).isEqualTo("Net Media")
        assertThat(cmd.adminEmail).isEqualTo("Budi@Net.ID")
        assertThat(cmd.adminName).isEqualTo("Budi")
        assertThat(cmd.monthlyFee).isNull()
        // Keunikan dicek pada nilai ternormalkan.
        assertThat(slugLookups).containsExactly("netmedia")
        assertThat(emailLookups).containsExactly("budi@net.id")
        // Hasil membawa slug tenant (sudah normal), tanpa membocorkan id internal.
        assertThat(result.slug).isEqualTo("netmedia")
        assertThat(result.adminEmail).isEqualTo("Budi@Net.ID")
    }

    @Test
    fun `slug yang sudah dipakai ditolak dan onboard tak dipanggil`() {
        takenSlugs += "netmedia"

        assertThatThrownBy {
            service.signup(baseCommand(slug = "NetMedia"))
        }.isInstanceOf(ConflictException::class.java).hasMessageContaining("Kode ISP")

        assertThat(onboard.commands).isEmpty()
    }

    @Test
    fun `email yang sudah terdaftar ditolak dan onboard tak dipanggil`() {
        takenEmails += "budi@net.id"

        assertThatThrownBy {
            service.signup(baseCommand(adminEmail = "Budi@Net.id"))
        }.isInstanceOf(ConflictException::class.java).hasMessageContaining("sudah terdaftar")

        assertThat(onboard.commands).isEmpty()
    }

    private fun baseCommand(
        slug: String = "netmedia",
        adminEmail: String = "budi@net.id",
    ) = SelfSignupCommand(
        slug = slug,
        name = "Net Media",
        adminEmail = adminEmail,
        adminName = "Budi",
        adminPassword = "rahasia123",
    )

    /** Onboard palsu: merekam command lalu memantulkan tenant dengan slug yang diminta. */
    private class RecordingOnboard : OnboardTenantUseCase {
        val commands = mutableListOf<OnboardTenantCommand>()
        override fun onboard(command: OnboardTenantCommand): OnboardTenantResult {
            commands += command
            return OnboardTenantResult(
                tenant = TenantRef(UuidV7.generate(), command.slug, command.name, TenantStatus.ACTIVE),
                adminUserCreated = true,
            )
        }
    }

    private inner class StubTenantApi : TenantApi {
        override fun findBySlug(slug: String): TenantRef? {
            slugLookups += slug
            return if (slug in takenSlugs) {
                TenantRef(UuidV7.generate(), slug, "Existing", TenantStatus.ACTIVE)
            } else {
                null
            }
        }

        override fun findById(id: UUID): TenantRef? = throw UnsupportedOperationException()
        override fun requireById(id: UUID): TenantRef = throw UnsupportedOperationException()
        override fun platformTenantId(): UUID = throw UnsupportedOperationException()
        override fun findActiveTenantIds(): List<UUID> = throw UnsupportedOperationException()
        override fun ensureTenant(slug: String, name: String): TenantRef = throw UnsupportedOperationException()
        override fun suspend(id: UUID): TenantRef = throw UnsupportedOperationException()
        override fun activate(id: UUID): TenantRef = throw UnsupportedOperationException()
    }

    private inner class StubUserDirectory : UserDirectory {
        override fun findTenantByEmail(emailLower: String): UUID? {
            emailLookups += emailLower
            return if (emailLower in takenEmails) UuidV7.generate() else null
        }
    }
}
