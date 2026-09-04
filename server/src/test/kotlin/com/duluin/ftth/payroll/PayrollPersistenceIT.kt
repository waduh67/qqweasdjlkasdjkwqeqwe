package com.duluin.ftth.payroll

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class PayrollPersistenceIT @Autowired constructor(private val jdbc: JdbcTemplate) {
    @Test
    fun `payroll schema has force RLS and durable transition tables`() {
        val tables = jdbc.queryForObject("select count(*) from pg_class where relname like 'payroll_%' and relkind = 'r' and relrowsecurity and relforcerowsecurity", Int::class.java)
        assertThat(tables).isGreaterThanOrEqualTo(13)
    }

    @Test
    @Transactional
    fun `tenant policy denies another tenant`() {
        val tenantOne = UUID.randomUUID()
        val tenantTwo = UUID.randomUUID()
        val periodId = UUID.randomUUID()
        jdbc.execute("select set_config('app.tenant_id', '$tenantOne', true)")
        jdbc.update("insert into payroll_period(id, tenant_id, valid_from, valid_to, pay_date) values (?, ?, '2026-01-01', '2026-01-31', '2026-02-01')", periodId, tenantOne)
        jdbc.execute("select set_config('app.tenant_id', '$tenantTwo', true)")
        val visible = jdbc.queryForObject("select count(*) from payroll_period where id = ?", Int::class.java, periodId)
        assertThat(visible).isZero()
    }
}
