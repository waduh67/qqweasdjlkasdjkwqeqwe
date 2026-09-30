package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseCommandJdbc
import com.duluin.ftth.inventory.application.service.WarehouseApprovalExpiry

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(WarehouseApprovalITLockDeadline.Configuration::class)
class WarehouseApprovalITLockDeadline : WarehouseApprovalHttpFixture() {
    @Autowired lateinit var probe: DeadlineProbe
    class DeadlineProbe(private val jdbc: WarehouseCommandJdbc) : WarehouseApprovalProbe {
        val pause = AtomicBoolean(false)
        val recovery = AtomicReference<(() -> Unit)?>(null)
        override fun reached(stage: WarehouseApprovalStage, requestId: UUID) {
            if (stage == WarehouseApprovalStage.DECISION && pause.getAndSet(false)) jdbc.execute { it.value("SELECT pg_sleep(14)") }
            if (stage == WarehouseApprovalStage.RECOVERY) recovery.getAndSet(null)?.invoke()
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    class Configuration { @Bean fun deadlineProbe(jdbc: WarehouseCommandJdbc) = DeadlineProbe(jdbc) }
    companion object {
        private val database by lazy { WarehouseSchemaDatabase() }
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }
    private fun deadline() {
        database.ownerFixture { connection -> connection.createStatement().use { statement ->
            statement.execute("""CREATE OR REPLACE FUNCTION approval_test_deadline() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN NEW.expires_at=clock_timestamp()+interval '10 seconds'; RETURN NEW; END ${'$'}${'$'}""")
            statement.execute("CREATE OR REPLACE TRIGGER warehouse_aaa_test_deadline BEFORE INSERT ON inventory_approval FOR EACH ROW EXECUTE FUNCTION approval_test_deadline()")
        } }
    }
    @ParameterizedTest @ValueSource(strings = ["BALANCE_TABLE", "LOT_KEY"])
    fun `AV12-1 expiry after observed admission lock wait commits only exact expired response`(mode: String) {
        deadline()
        val case = pending()
        val fixture = fixture(case.setup.token)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holderPid = java.util.concurrent.atomic.AtomicInteger()
        val key = UUID.randomUUID().toString()
        Executors.newFixedThreadPool(2).use { pool ->
            val holder = pool.submit { fixture.transaction {
                holderPid.set(scalar("SELECT pg_backend_pid()").toInt())
                if (mode == "BALANCE_TABLE") sql("LOCK TABLE inventory_balance_projection IN SHARE MODE")
                else scalar("SELECT pg_advisory_xact_lock(hashtextextended('$tenant|receipt-identity|LOT|${case.setup.cable}|LOT',0))::text")
                locked.countDown()
                check(release.await(40, TimeUnit.SECONDS))
            } }
            try {
                check(locked.await(10, TimeUnit.SECONDS))
                val decision = pool.submit<org.springframework.mock.web.MockHttpServletResponse> { decide(case, key = key) }
                await().atMost(Duration.ofSeconds(8)).until { fixture.transaction {
                    scalar("SELECT count(*) FROM pg_stat_activity WHERE ${holderPid.get()}=ANY(pg_blocking_pids(pid))").toInt() > 0
                } }
                fixture.transaction { scalar("SELECT pg_sleep(14)::text") }
                release.countDown()
                holder.get(10, TimeUnit.SECONDS)
                val result = decision.get(20, TimeUnit.SECONDS)
                assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
                assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo("EXPIRED")
                assertThat(decide(case, key = key).contentAsString).isEqualTo(result.contentAsString)
                counts(case, 0, 0)
                fixture.transaction {
                    assertThat(scalar("SELECT count(*) FROM inventory_lot")).isEqualTo("0")
                    assertThat(scalar("SELECT status||':'||revision::text FROM inventory_approval")).isEqualTo("EXPIRED:1")
                }
            } finally { release.countDown() }
        }
    }
    @Test fun `late expiry rolls back attempted decision before expiry worker wins recovery`() {
        deadline()
        val case = pending()
        val fixture = fixture(case.setup.token)
        probe.pause.set(true)
        probe.recovery.set {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            fixture.transaction {
                for (table in listOf("inventory_approval_decision", "inventory_approval_effect", "inventory_movement", "inventory_movement_leg"))
                    assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
            }
            com.duluin.ftth.common.tenant.TenantContext.runAs(fixture.tenant) {
                assertThat(context.getBean(WarehouseApprovalExpiry::class.java).expireOne()).isTrue()
            }
        }
        try {
            val key = UUID.randomUUID().toString()
            val response = decide(case, key = key)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
            assertThat(mapper.readTree(response.contentAsString).path("status").asString()).isEqualTo("EXPIRED")
            assertThat(probe.recovery.get() == null).isTrue()
            assertThat(decide(case, key = key).contentAsString).isEqualTo(response.contentAsString)
            counts(case, 0, 0)
            fixture.transaction {
                assertThat(scalar("SELECT revision FROM inventory_approval WHERE id='${case.id}'")).isEqualTo("1")
                assertThat(scalar("SELECT terminal_body FROM inventory_approval WHERE id='${case.id}'")).isEqualTo(response.contentAsString)
            }
        } finally { probe.pause.set(false); probe.recovery.set(null) }
    }
}
