package com.duluin.ftth.common.infrastructure.security

import com.duluin.ftth.common.security.CurrentUserProvider
import org.springframework.stereotype.Component

/**
 * Dipakai di anotasi keamanan method: `@PreAuthorize("@authz.can('iam.role.create')")`.
 *
 * Terpusat di satu tempat sehingga aturan "platform admin melewati semua izin"
 * konsisten, dan pengecekan izin terbaca jelas di setiap use case.
 */
@Component("authz")
class AccessChecker(
    private val currentUser: CurrentUserProvider,
) {
    fun can(permissionCode: String): Boolean =
        currentUser.currentOrNull()?.hasPermission(permissionCode) ?: false

    /** True bila SEMUA izin dimiliki. */
    fun canAll(vararg permissionCodes: String): Boolean {
        val user = currentUser.currentOrNull() ?: return false
        return permissionCodes.all(user::hasPermission)
    }

    /** True bila MINIMAL SATU izin dimiliki. */
    fun canAny(vararg permissionCodes: String): Boolean {
        val user = currentUser.currentOrNull() ?: return false
        return permissionCodes.any(user::hasPermission)
    }

    fun isPlatformAdmin(): Boolean = currentUser.currentOrNull()?.platformAdmin ?: false
}
