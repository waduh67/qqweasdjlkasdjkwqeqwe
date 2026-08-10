package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.iam.application.port.inbound.SelfSignupCommand
import com.duluin.ftth.iam.application.port.inbound.SelfSignupResult
import com.duluin.ftth.iam.application.port.inbound.SelfSignupUseCase
import com.duluin.ftth.iam.application.port.outbound.UserDirectory
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service

/**
 * Pendaftaran mandiri ISP: menjaga keunikan slug + email DI DEPAN, lalu memakai ulang
 * [OnboardTenantUseCase] yang sudah teruji (tenant + role "Tenant Admin" + admin awal +
 * langganan trial lewat event). Tenant langsung AKTIF dengan periode langganan awal satu
 * bulan (de-facto trial) — platform admin bisa menyuspensi bila menyalahgunakan.
 *
 * Endpoint publik ini rawan spam, jadi laju pendaftaran per-IP direm di adapter
 * ([com.duluin.ftth.iam.adapter.inbound.web.SignupController] →
 * [com.duluin.ftth.common.infrastructure.security.AttemptThrottle]). Rem itu menahan
 * banjir dari satu sumber, bukan pendaftar tekun berganti IP — untuk itu tetap tersedia
 * suspensi manual oleh platform admin.
 */
@Service
class SelfSignupService(
    private val tenantApi: TenantApi,
    private val userDirectory: UserDirectory,
    private val onboarding: OnboardTenantUseCase,
) : SelfSignupUseCase {

    override fun signup(command: SelfSignupCommand): SelfSignupResult {
        val slug = command.slug.trim().lowercase()
        val emailLower = command.adminEmail.trim().lowercase()

        // Keunikan slug: tenant table tanpa RLS, aman diquery tanpa tenant context.
        if (tenantApi.findBySlug(slug) != null) {
            throw ConflictException("Kode ISP '$slug' sudah dipakai. Silakan pilih kode lain.")
        }
        // Keunikan email GLOBAL (1 email = 1 tenant): dicek lewat direktori pra-auth yang
        // memang diquery sebelum tenant context terpasang. Mencegah tenant yatim (admin gagal
        // dibuat karena email sudah ada di tenant lain).
        if (userDirectory.findTenantByEmail(emailLower) != null) {
            throw ConflictException("Email '${command.adminEmail.trim()}' sudah terdaftar. Silakan masuk.")
        }

        val result = onboarding.onboard(
            OnboardTenantCommand(
                slug = slug,
                name = command.name.trim(),
                // Email dikirim ter-trim agar yang tersimpan == yang dicek keunikannya
                // (spasi di tepi tak boleh mem-bypass cek di atas).
                adminEmail = command.adminEmail.trim(),
                adminName = command.adminName.trim(),
                adminPassword = command.adminPassword,
                // Publik tak menentukan harga; harga default global yang berlaku.
                monthlyFee = null,
            ),
        )
        return SelfSignupResult(
            slug = result.tenant.slug,
            name = result.tenant.name,
            adminEmail = command.adminEmail.trim(),
        )
    }
}
