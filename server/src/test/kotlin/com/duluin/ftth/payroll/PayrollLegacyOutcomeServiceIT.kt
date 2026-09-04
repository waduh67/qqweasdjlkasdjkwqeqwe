package com.duluin.ftth.payroll

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.payroll.application.service.PayrollCommandService
import com.duluin.ftth.payroll.domain.PayrollCanonical
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class PayrollLegacyOutcomeServiceIT @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val payroll: PayrollCommandService,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @AfterEach
    fun clearSecurity() {
        SecurityContextHolder.clearContext()
        TenantContext.clear()
    }

    @Test
    fun `legacy outcome through service returns reconciliation conflict without effect`() {
        val fixture = fixture("legacy", "{\"runId\":\"${UUID.randomUUID()}\",\"state\":\"CALCULATED\"}")
        authenticate(fixture)

        assertThatThrownBy { TenantContext.runAs(fixture.tenant) { payroll.calculate(fixture.runId, fixture.employee, fixture.period, fixture.operationKey, fixture.payloadHash) } }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("legacy payroll outcome requires reconciliation")
        assertNoEffects(fixture)
    }

    @Test
    fun `malformed outcome through service maps to reconciliation conflict`() {
        val fixture = fixture("malformed", "{\"unsupported\":true}")
        authenticate(fixture)

        assertThatThrownBy { TenantContext.runAs(fixture.tenant) { payroll.calculate(fixture.runId, fixture.employee, fixture.period, fixture.operationKey, fixture.payloadHash) } }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("unsupported payroll outcome requires reconciliation")
        assertNoEffects(fixture)
    }

    @Test
    fun `legacy outcome cannot cross tenant service boundary`() {
        val fixture = fixture("cross-tenant", "{\"runId\":\"${UUID.randomUUID()}\",\"state\":\"CALCULATED\"}")
        val other = fixture.copy(tenant = UUID.randomUUID())
        authenticate(other)

        assertThatThrownBy { TenantContext.runAs(other.tenant) { payroll.calculate(fixture.runId, fixture.employee, fixture.period, fixture.operationKey, fixture.payloadHash) } }
            .isInstanceOf(ConflictException::class.java)
    }

    private fun fixture(suffix: String, payload: String): Fixture {
        val tenant = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val period = UUID.randomUUID()
        val run = UUID.randomUUID()
        val operationKey = "legacy-$suffix-${run.toString().take(8)}"
        val hash = PayrollCanonical.hash("payroll.run.calculate", tenant, actor, mapOf("runId" to run.toString(), "employeeId" to employee.toString(), "periodId" to period.toString(), "operationKey" to operationKey))
        transactions.execute {
            jdbc.execute("select set_config('app.tenant_id', '$tenant', true)")
            jdbc.update("insert into payroll_period(id,tenant_id,valid_from,valid_to,pay_date) values(?,?, '2026-01-01','2026-01-31','2026-02-01')", period, tenant)
            jdbc.update("insert into payroll_run(id,tenant_id,requester_id,period_id,operation_key,payload_hash,state,approval_tiers,created_at,updated_at) values(?,?,?,?,'fixture-$run','hash','DRAFT','[]',now(),now())", run, tenant, actor, period)
            jdbc.update("insert into payroll_operation_outcome(tenant_id,namespace,operation_key,payload_hash,status,outcome,created_at) values(?, 'payroll.run.calculate', ?, ?, 'COMPLETED', ?::jsonb, now())", tenant, operationKey, hash, payload)
        }
        return Fixture(tenant, actor, employee, period, run, operationKey, hash)
    }

    private fun authenticate(fixture: Fixture) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            AuthenticatedUser(fixture.actor, fixture.tenant, "payroll-test@local", "Payroll Test", false, setOf("payroll.run.calculate"), emptySet()),
            null,
            emptyList(),
        )
    }

    private fun assertNoEffects(fixture: Fixture) {
        transactions.execute {
            jdbc.execute("select set_config('app.tenant_id', '${fixture.tenant}', true)")
            assert(jdbc.queryForObject("select count(*) from payroll_payment where run_id=?", Int::class.java, fixture.runId) == 0)
            assert(jdbc.queryForObject("select count(*) from payroll_void where run_id=?", Int::class.java, fixture.runId) == 0)
        }
    }

    private data class Fixture(val tenant: UUID, val actor: UUID, val employee: UUID, val period: UUID, val runId: UUID, val operationKey: String, val payloadHash: String)
}
