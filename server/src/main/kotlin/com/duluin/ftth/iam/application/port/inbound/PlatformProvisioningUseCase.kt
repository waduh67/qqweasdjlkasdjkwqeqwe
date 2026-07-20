package com.duluin.ftth.iam.application.port.inbound

/** Menyiapkan tenant platform + platform admin pertama (dipakai bootstrap). */
interface PlatformProvisioningUseCase {

    /** @return true bila platform admin baru dibuat, false bila sudah ada. */
    fun ensurePlatformAdmin(command: PlatformAdminCommand): Boolean
}

data class PlatformAdminCommand(
    val email: String,
    val name: String,
    val password: String,
)
