package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.authorizeChange
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TechnicianPasswordService(
    private val users: UserRepository,
    private val roles: RoleRepository,
    private val owners: TenantOwnerStore,
    private val passwords: PasswordResetService,
    private val authority: CurrentAuthorityApi,
) {
    @Transactional
    fun reset(id: UUID, password: String) {
        authority.authorizeChange("warehouse.technician.manage")
        val user = users.findById(id) ?: throw NotFoundException("Teknisi tidak ditemukan")
        val technician = roles.findAllByIds(user.roleIds).any {
            it.defaultKey in setOf("TECHNICIAN_NE", "TECHNICIAN_FO", "TECHNICIAN_LEGACY")
        }
        if (user.platformAdmin || owners.findUserId() == id || !technician) {
            throw ValidationException("Reset ini hanya untuk akun teknisi")
        }
        passwords.reset(user, password)
    }
}
