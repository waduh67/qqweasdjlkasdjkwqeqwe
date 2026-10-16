package com.karuhun.ftth.iam.application.service

import com.karuhun.ftth.common.domain.error.AccessDeniedException
import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.common.security.CurrentUserProvider
import com.karuhun.ftth.iam.CurrentAuthorityApi
import com.karuhun.ftth.iam.authorizeChange
import com.karuhun.ftth.iam.application.port.outbound.PasswordHasher
import com.karuhun.ftth.iam.application.port.outbound.TenantOwnerStore
import com.karuhun.ftth.iam.application.port.outbound.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class AccountPasswordService(
    private val users: UserRepository,
    private val owners: TenantOwnerStore,
    private val hasher: PasswordHasher,
    private val passwords: PasswordResetService,
    private val current: CurrentUserProvider,
    private val authority: CurrentAuthorityApi,
) {
    fun changeOwn(currentPassword: String, newPassword: String) {
        authority.lockForChange()
        authority.lockCurrent()
        val user = users.findById(current.current().userId) ?: throw NotFoundException("Pengguna tidak ditemukan")
        if (currentPassword.toByteArray(Charsets.UTF_8).size > 72 || !hasher.matches(currentPassword, user.passwordHash)) {
            throw ValidationException("Password saat ini tidak sesuai")
        }
        passwords.reset(user, newPassword, "user.password_changed")
    }

    fun resetUser(id: UUID, newPassword: String) {
        authority.authorizeChange("iam.user.resetpassword")
        val user = users.findById(id) ?: throw NotFoundException("Pengguna tidak ditemukan")
        if (id == current.current().userId) throw ValidationException("Gunakan Ganti password di Keamanan akun")
        if (user.platformAdmin || owners.findUserId() == id) {
            throw AccessDeniedException("Reset password owner dilakukan oleh admin platform")
        }
        passwords.reset(user, newPassword)
    }
}
