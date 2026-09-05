package com.duluin.ftth.hris

import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.hris.adapter.outbound.persistence.HrisEmployeeJpaEntity
import com.duluin.ftth.hris.adapter.outbound.persistence.HrisEmployeeJpaRepository
import com.duluin.ftth.hris.application.port.*
import com.duluin.ftth.hris.application.service.HrisAuthenticatedAttendanceService
import com.duluin.ftth.hris.application.service.HrisAttendanceService
import com.duluin.ftth.hris.domain.EmployeeProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class HrisApplicationTest {
    @Test
    fun `authenticated self check-in derives actor and rejects gps-only authority`() {
        val tenant = UUID.randomUUID(); val user = UUID.randomUUID()
        val current = object : CurrentUserProvider {
            override fun currentOrNull() = AuthenticatedUser(user, tenant, "employee@example.test", "Employee", false, setOf("hris.employee.self"), emptySet())
        }
        val employee = HrisEmployeeJpaEntity()
        val employees = mock(HrisEmployeeJpaRepository::class.java)
        `when`(employees.findByUserId(user)).thenReturn(employee)
        val sessions = InMemoryHrisAttendanceRepository()
        val corrections = InMemoryHrisCorrectionRepository()
        val app = HrisAuthenticatedAttendanceService(current, employees, sessions, corrections, HrisAttendanceService(sessions, corrections), object : HrisPolicyRepository {
            override fun resolve(employeeId: UUID, workDate: LocalDate) = HrisPolicySnapshot(
                EmployeeProfile(employeeId, tenant, user, EmployeeStatus.ACTIVE, null), null, null, emptyList(), workDate,
            )
        })
        val result = app.selfCheckIn("app-one", "a".repeat(64), true)
        assertThat(result.decision).isEqualTo(AttendanceDecision.REJECTED)
        assertThat(result.tenantId).isEqualTo(tenant)
    }
}
