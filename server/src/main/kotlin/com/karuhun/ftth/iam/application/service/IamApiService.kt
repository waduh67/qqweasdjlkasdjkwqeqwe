package com.karuhun.ftth.iam.application.service

import com.karuhun.ftth.iam.AreaRef
import com.karuhun.ftth.iam.AreaReferenceApi
import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.common.security.AuthorityScope
import com.karuhun.ftth.iam.IamApi
import com.karuhun.ftth.iam.UserRef
import com.karuhun.ftth.iam.application.port.outbound.AreaRepository
import com.karuhun.ftth.iam.application.port.outbound.RoleRepository
import com.karuhun.ftth.iam.application.port.outbound.UserDirectory
import com.karuhun.ftth.iam.application.port.outbound.UserRepository
import com.karuhun.ftth.iam.domain.model.Area
import com.karuhun.ftth.iam.domain.model.User
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
    private val owners: com.karuhun.ftth.iam.application.port.outbound.TenantOwnerStore,
) : IamApi, AreaReferenceApi, com.karuhun.ftth.iam.NeTechnicianApi, com.karuhun.ftth.iam.FieldTeamApi {

    override fun ownerId(): UUID? = owners.findUserId()
    override fun canViewTeam(userId: UUID): Boolean = userRepository.findById(userId)?.let { user ->
        user.active && !user.platformAdmin && (owners.findUserId() == userId ||
            roleRepository.findAllByIds(user.roleIds).any { it.defaultKey in setOf("ADMIN", "MANAGER") })
    } == true

    override fun technicians(): List<com.karuhun.ftth.iam.FieldTechnician> {
        val owner = owners.findUserId()
        val result = mutableListOf<com.karuhun.ftth.iam.FieldTechnician>()
        var page = 0
        do {
            val users = userRepository.search(null, PageRequest(page++, 100))
            users.content.filter { it.active && !it.platformAdmin && it.id != owner }.forEach { user ->
                val keys = roleRepository.findAllByIds(user.roleIds).map { it.defaultKey }.toSet()
                val team = if (keys.isEmpty() || keys.any { it !in setOf("TECHNICIAN_NE", "TECHNICIAN_FO") }) null
                    else if ("TECHNICIAN_FO" in keys) com.karuhun.ftth.iam.FieldTeam.FO else com.karuhun.ftth.iam.FieldTeam.NE
                if (team != null) result.add(com.karuhun.ftth.iam.FieldTechnician(user.id, user.name, team))
            }
        } while (page.toLong() * 100 < users.totalElements)
        return result.sortedWith(compareBy({ it.team }, { it.name }, { it.id }))
    }

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
