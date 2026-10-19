package com.karuhun.ftth.iam

import java.util.UUID

interface FieldTeamApi {
    fun ownerId(): UUID?
    fun canViewTeam(userId: UUID): Boolean
    fun technicians(): List<FieldTechnician>
}
enum class FieldTeam { NE, FO }
data class FieldTechnician(val id: UUID, val name: String, val team: FieldTeam)
