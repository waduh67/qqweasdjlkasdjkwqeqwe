package com.duluin.ftth.iam.application.port.inbound

import com.duluin.ftth.tenancy.TenantRef

/**
 * Onboarding tenant: buat tenant (via module tenancy) + role "Tenant Admin"
 * berisi semua izin tenant + user admin pertama. Dipakai platform admin dan
 * seeder demo. Idempotent terhadap slug tenant yang sudah ada.
 */
interface OnboardTenantUseCase {

    fun onboard(command: OnboardTenantCommand): OnboardTenantResult
}

data class OnboardTenantCommand(
    val slug: String,
    val name: String,
    val adminEmail: String,
    val adminName: String,
    val adminPassword: String,
)

data class OnboardTenantResult(
    val tenant: TenantRef,
    val adminUserCreated: Boolean,
)
