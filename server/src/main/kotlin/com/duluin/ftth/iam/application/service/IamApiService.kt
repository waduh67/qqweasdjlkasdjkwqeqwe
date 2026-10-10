package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.AreaRef
import com.duluin.ftth.iam.AreaReferenceApi
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.security.AuthorityScope
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
    private val owners: com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore,
) : IamApi, AreaReferenceApi, com.duluin.ftth.iam.NeTechnicianApi {

    override fun searchNetworkEngineers(query: String, page: PageRequest): Page<UserRef> =
        userRepository.searchNetworkEngineers(query, page).map { it.toRef() }

    override fun findUser(id: UUID): UserRef? = userRepository.findById(id)?.toRef()

    override fun usersByIds(ids: Set<UUID>): List<UserRef> =
        userRepository.findAllByIds(ids).map { it.toRef() }

    override fun primaryEmailForTenant(tenantId: UUID): String? =
        userDirectory.primaryEmailForTenant(tenantId)

    override fun areasByIds(ids: Set<UUID>): List<AreaRef> =
        if (ids.isEmpty()) emptyList() else areaRepository.findAllByIds(ids).map { it.toRef() }

    override fun areasInScope(scope: AuthorityScope): List<AreaRef> = when (scope) {
        AuthorityScope.Unrestricted -> areaRepository.findAll().map { it.toRef() }
        is AuthorityScope.Restricted -> areasByIds(scope.ids)
    }.sortedBy { it.name }

    override fun searchAreas(scope: AuthorityScope, query: String, page: PageRequest): Page<AreaRef> =
        areaRepository.search(scope, query, page).map { it.toRef() }

    private fun User.toRef() = UserRef(
        id = id,
        name = name,
        email = email.value,
        active = active,
        technician = roleRepository.findAllByIds(roleIds).any {
            it.defaultKey in setOf("TECHNICIAN_LEGACY", "TECHNICIAN_NE", "TECHNICIAN_FO") || it.name == "Teknisi"
        },
        pureTechnician = !platformAdmin && roleIds.isNotEmpty() && roleRepository.findAllByIds(roleIds).all {
            it.defaultKey in setOf("TECHNICIAN_NE", "TECHNICIAN_FO")
        },
        pureNetworkEngineer = !platformAdmin && owners.findUserId() != id && roleIds.isNotEmpty() &&
            roleRepository.findAllByIds(roleIds).all { it.defaultKey == "TECHNICIAN_NE" },
    )

    private fun Area.toRef() = AreaRef(id = id, code = code, name = name)
}
