package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.AreaRef
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.iam.UserRef
import com.duluin.ftth.iam.application.port.outbound.AreaRepository
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.application.port.outbound.UserDirectory
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.Area
import com.duluin.ftth.iam.domain.model.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class IamApiService(
    private val userRepository: UserRepository,
    private val userDirectory: UserDirectory,
    private val areaRepository: AreaRepository,
    private val roleRepository: RoleRepository,
) : IamApi {

    override fun findUser(id: UUID): UserRef? = userRepository.findById(id)?.toRef()

    override fun usersByIds(ids: Set<UUID>): List<UserRef> =
        userRepository.findAllByIds(ids).map { it.toRef() }

    override fun primaryEmailForTenant(tenantId: UUID): String? =
        userDirectory.primaryEmailForTenant(tenantId)

    override fun areasByIds(ids: Set<UUID>): List<AreaRef> =
        if (ids.isEmpty()) emptyList() else areaRepository.findAllByIds(ids).map { it.toRef() }

    private fun User.toRef() = UserRef(
        id = id,
        name = name,
        email = email.value,
        active = active,
        technician = roleRepository.findAllByIds(roleIds).any { it.name == "Teknisi" },
    )

    private fun Area.toRef() = AreaRef(id = id, code = code, name = name)
}
