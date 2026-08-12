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
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

/**
 * Kode ISP tak lagi diketik pendaftar — service yang menurunkannya dari nama. Yang dijaga di
 * sini: bentuknya selalu sah menurut `Tenant.create`, bentrok tak pernah bocor sebagai galat
 * ke pendaftar (ia tak punya cara memperbaikinya), dan email kembar TETAP ditolak — slug
 * kembar dulu bisa menyuntik admin baru ke tenant orang lain, email kembar masih bisa.
 */
class SelfSignupServiceTest {

    private val takenSlugs = mutableSetOf<String>()
    private val takenEmails = mutableSetOf<String>()
    private val emailLookups = mutableListOf<String>()
    private val onboard = RecordingOnboard()

    private val service = SelfSignupService(StubTenantApi(), StubUserDirectory(), onboard)

    @Test
    fun `kode ISP diturunkan dari nama dan nilai lain dinormalkan`() {
        val result = service.signup(
            SelfSignupCommand(
                name = "  PT Net Media Jaya  ",
                adminEmail = "  Budi@Net.ID ",
                adminName = "  Budi  ",
                adminPassword = "rahasia123",
            ),
        )

        val cmd = onboard.commands.single()
        assertThat(cmd.slug).isEqualTo("pt-net-media-jaya")
        assertThat(cmd.name).isEqualTo("PT Net Media Jaya")
        assertThat(cmd.adminEmail).isEqualTo("Budi@Net.ID")
        assertThat(cmd.adminName).isEqualTo("Budi")
        // null = pakai harga bawaan dari /platform/billing; harga tak pernah datang dari pendaftar.
        assertThat(cmd.monthlyFee).isNull()
        assertThat(emailLookups).containsExactly("budi@net.id")
        // Pendaftar harus menerima kodenya — ia diminta setiap kali staf masuk.
        assertThat(result.slug).isEqualTo("pt-net-media-jaya")
        assertThat(result.adminEmail).isEqualTo("Budi@Net.ID")
    }

    @Test
    fun `nama yang sama menghasilkan kode berakhiran angka, bukan galat`() {
        takenSlugs += "net-media"

        val result = service.signup(baseCommand())

        assertThat(result.slug).isEqualTo("net-media-2")
        assertThat(onboard.commands.single().slug).isEqualTo("net-media-2")
    }

    @Test
    fun `nama tanpa huruf jatuh ke kode acak berawalan isp`() {
        val result = service.signup(baseCommand(name = "123 456"))

        assertThat(result.slug).matches("isp-[a-z0-9]{6}")
    }

    @Test
    fun `angka dan simbol di awal nama dibuang agar kode diawali huruf`() {
        // Tenant.create menuntut ^[a-z][a-z0-9-]{1,62}$ — generator harus tak pernah melanggarnya.
        val result = service.signup(baseCommand(name = "24 Jam Net"))

        assertThat(result.slug).isEqualTo("jam-net")
    }

    @Test
    fun `balapan yang menembus unique index dicoba ulang dengan kode lain`() {
        // Dua pendaftaran serempak lolos cek findBySlug bersamaan; index yang memutuskan.
        onboard.failFirst = true

        val result = service.signup(baseCommand())

        assertThat(onboard.commands).hasSize(2)
        assertThat(onboard.commands[0].slug).isEqualTo("net-media")
        assertThat(result.slug).isEqualTo("net-media-2")
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
        name: String = "Net Media",
        adminEmail: String = "budi@net.id",
    ) = SelfSignupCommand(
        name = name,
        adminEmail = adminEmail,
        adminName = "Budi",
        adminPassword = "rahasia123",
    )

    /** Onboard palsu: merekam command lalu memantulkan tenant dengan slug yang diminta. */
    private inner class RecordingOnboard : OnboardTenantUseCase {
        val commands = mutableListOf<OnboardTenantCommand>()

        /** Meniru unique index yang kalah balapan pada percobaan pertama. */
        var failFirst = false

        override fun onboard(command: OnboardTenantCommand): OnboardTenantResult {
            commands += command
            if (failFirst && commands.size == 1) {
                // Pemenang balapan kini benar-benar ada; percobaan kedua harus melihatnya.
                takenSlugs += command.slug
                throw DataIntegrityViolationException("duplicate slug")
            }
            return OnboardTenantResult(
                tenant = TenantRef(UuidV7.generate(), command.slug, command.name, TenantStatus.ACTIVE),
                adminUserCreated = true,
            )
        }
    }

    private inner class StubTenantApi : TenantApi {
        override fun findBySlug(slug: String): TenantRef? =
            if (slug in takenSlugs) TenantRef(UuidV7.generate(), slug, "Existing", TenantStatus.ACTIVE) else null

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

        override fun primaryEmailForTenant(tenantId: UUID): String? = null
    }
}
