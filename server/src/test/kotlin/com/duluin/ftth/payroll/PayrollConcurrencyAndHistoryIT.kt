package com.duluin.ftth.payroll

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest
@ActiveProfiles("test")
class PayrollConcurrencyAndHistoryIT @Autowired constructor(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `concurrent approval locks run and only one transition wins`() {
        val fixture = fixture()
        tenant(fixture.tenant)
        jdbc.update("update payroll_run set state='REVIEWED' where id=?", fixture.runId)
        val ready = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        val results = (1..2).map {
            pool.submit<Int> {
                ready.countDown()
                ready.await()
                transactions.execute {
                    tenant(fixture.tenant)
                    jdbc.queryForObject("select state from payroll_run where id=? for update", String::class.java, fixture.runId)
                    jdbc.update("update payroll_run set state='APPROVED' where id=? and state='REVIEWED'", fixture.runId)
                } ?: 0
            }
        }.map { it.get() }
        pool.shutdown()
        assertThat(results.sum()).isEqualTo(1)
        assertThat(transactions.execute { tenant(fixture.tenant); jdbc.queryForObject("select state from payroll_run where id=?", String::class.java, fixture.runId) }).isEqualTo("APPROVED")
    }

    @Test
    fun `concurrent payment has one durable effect`() {
        val fixture = fixture()
        tenant(fixture.tenant)
        val pool = Executors.newFixedThreadPool(2)
        val results = (1..2).map {
            pool.submit<Boolean> {
                try {
                    transactions.execute {
                        tenant(fixture.tenant)
                        jdbc.update("insert into payroll_payment(id,tenant_id,run_id,operation_key,amount_minor,currency,paid_at) values(?,?,?,?,?,?,now())", UUID.randomUUID(), fixture.tenant, fixture.runId, "pay-${fixture.runId}", 100L, "IDR")
                    }
                    true
                } catch (_: DataAccessException) { false }
            }
        }.map { it.get() }
        pool.shutdown()
        assertThat(results.count { it }).isEqualTo(1)
        assertThat(transactions.execute { tenant(fixture.tenant); jdbc.queryForObject("select count(*) from payroll_payment where run_id=?", Int::class.java, fixture.runId) }).isEqualTo(1)
    }

    @Test
    fun `concurrent void has one compensating record`() {
        val fixture = fixture()
        tenant(fixture.tenant)
        val pool = Executors.newFixedThreadPool(2)
        val results = (1..2).map {
            pool.submit<Boolean> {
                try {
                    transactions.execute {
                        tenant(fixture.tenant)
                        jdbc.update("insert into payroll_void(id,tenant_id,run_id,operation_key,reversal_of,actor_id,reason,voided_at) values(?,?,?,?,?,?,?,now())", UUID.randomUUID(), fixture.tenant, fixture.runId, "void-${fixture.runId}", fixture.runId, fixture.approver, "reversal")
                    }
                    true
                } catch (_: DataAccessException) { false }
            }
        }.map { it.get() }
        pool.shutdown()
        assertThat(results.count { it }).isEqualTo(1)
        assertThat(transactions.execute { tenant(fixture.tenant); jdbc.queryForObject("select count(*) from payroll_void where run_id=?", Int::class.java, fixture.runId) }).isEqualTo(1)
    }

    @Test
    fun `compensation component and deduction history reject update and delete`() {
        val fixture = fixture()
        val compensation = UUID.randomUUID()
        val component = UUID.randomUUID()
        val deduction = UUID.randomUUID()
        transactions.execute {
            tenant(fixture.tenant)
            jdbc.update("insert into payroll_compensation(id,tenant_id,employee_id,valid_from,currency,monthly_base_minor,hourly_rate_minor,created_at) values(?,?,?,'2026-01-01','IDR',100,10,now())", compensation, fixture.tenant, fixture.employee)
            jdbc.update("insert into payroll_component(id,tenant_id,employee_id,code,kind,amount_minor,currency,valid_from) values(?,?,?,'meal','ALLOWANCE',10,'IDR','2026-01-01')", component, fixture.tenant, fixture.employee)
            jdbc.update("insert into payroll_deduction_rule(id,tenant_id,code,kind,rate) values(?,?,?,'TAX',1)", deduction, fixture.tenant, "tax_${fixture.runId.toString().take(20)}")
        }
        assertThatThrownBy { transactions.execute { tenant(fixture.tenant); jdbc.update("update payroll_compensation set monthly_base_minor=200 where id=?", compensation) } }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { transactions.execute { tenant(fixture.tenant); jdbc.update("delete from payroll_compensation where id=?", compensation) } }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { transactions.execute { tenant(fixture.tenant); jdbc.update("update payroll_component set amount_minor=20 where id=?", component) } }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { transactions.execute { tenant(fixture.tenant); jdbc.update("delete from payroll_component where id=?", component) } }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { transactions.execute { tenant(fixture.tenant); jdbc.update("update payroll_deduction_rule set rate=2 where id=?", deduction) } }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { transactions.execute { tenant(fixture.tenant); jdbc.update("delete from payroll_deduction_rule where id=?", deduction) } }.isInstanceOf(DataAccessException::class.java)
    }

    private fun fixture(): Fixture {
        val tenant = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val approver = UUID.randomUUID()
        val period = UUID.randomUUID()
        val run = UUID.randomUUID()
        transactions.execute {
            tenant(tenant)
            jdbc.update("insert into payroll_period(id,tenant_id,valid_from,valid_to,pay_date) values(?,?, '2026-01-01','2026-01-31','2026-02-01')", period, tenant)
            jdbc.update("insert into payroll_run(id,tenant_id,requester_id,period_id,operation_key,payload_hash,state,approval_tiers,created_at,updated_at) values(?,?,?,?,'fixture-${run}','hash','APPROVED','[]',now(),now())", run, tenant, employee, period)
        }
        return Fixture(tenant, employee, approver, period, run)
    }

    private fun tenant(id: UUID) { jdbc.execute("select set_config('app.tenant_id', '$id', false)") }
    private data class Fixture(val tenant: UUID, val employee: UUID, val approver: UUID, val period: UUID, val runId: UUID)
}
