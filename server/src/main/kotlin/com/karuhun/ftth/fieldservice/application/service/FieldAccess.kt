package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.AccessDeniedException
import com.karuhun.ftth.common.domain.error.SubscriptionLockedException
import com.karuhun.ftth.common.security.ReadOnlyLockGuard
import com.karuhun.ftth.iam.CurrentAuthority
import com.karuhun.ftth.iam.CurrentAuthorityApi
import com.karuhun.ftth.iam.FieldTeamApi
import com.karuhun.ftth.iam.FieldTechnician
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class FieldAccess(private val authority: CurrentAuthorityApi, private val team: FieldTeamApi,
    private val readOnly: ObjectProvider<ReadOnlyLockGuard>) {
    fun actor(): CurrentAuthority = authority.lockCurrent().also { it.fence.assertHeld() }
    fun permission(actor: CurrentAuthority, code: String, write: Boolean = false) {
        if (!actor.platformAdmin && code !in actor.permissions) throw AccessDeniedException("Tidak memiliki izin operasional")
        if (write && readOnly.getIfAvailable()?.isReadOnly() == true) throw SubscriptionLockedException()
    }
    fun owner(actor: CurrentAuthority, write: Boolean = false) {
        if (actor.fence.identity.userId != team.ownerId()) throw AccessDeniedException("Pengaturan operasional hanya untuk owner tenant")
        if (write && readOnly.getIfAvailable()?.isReadOnly() == true) throw SubscriptionLockedException()
    }
    fun scope(actor: CurrentAuthority, own: String, all: String): List<FieldTechnician> {
        val members = team.technicians()
        val technician = members.singleOrNull { it.id == actor.fence.identity.userId }
        if (technician != null) { permission(actor, own); return listOf(technician) }
        if (!team.canViewTeam(actor.fence.identity.userId)) throw AccessDeniedException("KPI tim hanya untuk owner, Admin atau Manager")
        permission(actor, all)
        return members
    }
    fun technician(actor: CurrentAuthority, code: String): FieldTechnician {
        permission(actor, code, true)
        return team.technicians().singleOrNull { it.id == actor.fence.identity.userId }
            ?: throw AccessDeniedException("Laporan harian hanya untuk Teknisi NE atau FO tanpa role lain")
    }
}
