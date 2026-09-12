package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.PostingJdbcProbe
import com.duluin.ftth.inventory.TestPostingPhase
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseMaterialFactJpaEntity
import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WorkOrderMaterialUsageITIsolation : MaterialUsageFixture() {
    @ParameterizedTest @EnumSource(value = TestPostingPhase::class, names = ["HEADER", "MATERIAL_FACT", "FACTS"])
    fun `database rejects a missing posting fact or usage snapshot at transaction completion`(phase: TestPostingPhase) {
        val case = usageCase()
        val before = usageAccounting(case)

        PostingJdbcProbe(context, phase, omitWrite = true) {}.use { probe ->
            assertThatThrownBy { use(case) }.isInstanceOf(Exception::class.java)
            assertThat(probe.observations).isEqualTo(1)
        }

        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @Test fun `composite receipt reference rejects foreign tenant acceptance`() {
        val case = usageCase()
        val other = usageCase()
        val foreignLine = other.input.lines.single()
        val ownLine = case.input.lines.single()

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            val usage = UUID.randomUUID()
            val planLine = scalar("SELECT id FROM inventory_material_plan_line LIMIT 1")
            sql("""INSERT INTO inventory_material_usage(id,tenant_id,actor_id,evidence_reference,recorded_at)
                VALUES ('$usage',current_setting('app.tenant_id')::uuid,'${case.receipt.receiver.second}','evidence',clock_timestamp())""")
            sql("""INSERT INTO inventory_material_usage_line(id,tenant_id,usage_id,receipt_id,issue_line_id,plan_line_id,
                source_identity_id,source_revision,consumed_identity_id,remainder_identity_id,fact_id,requested_base,acknowledged_base,used_base,residual_base,base_unit)
                VALUES ('${ownLine.issueLineId}',current_setting('app.tenant_id')::uuid,'$usage','${foreignLine.receiptId}','${foreignLine.issueLineId}',
                    '$planLine','${ownLine.stockIdentityId}',0,'${ownLine.stockIdentityId}','${ownLine.stockIdentityId}',gen_random_uuid(),100000,100000,1,99999,'MM')""")
        } }.hasStackTraceContaining("foreign key constraint")
    }

    @Test fun `standalone material fact retains nullable customer and immutable usage linkage through JPA`() {
        val case = usageCase()
        val snapshot = mapper.readTree(used(case))

        fixture(case.receipt.stock.token).transaction {
            val manager = requireNotNull(EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory::class.java)))
            val fact = manager.find(WarehouseMaterialFactJpaEntity::class.java, UUID.fromString(snapshot.path("lines")[0].path("factId").asString()))
            assertThat(fact.customerId).isNull()
            assertThat(fact.usageId).isEqualTo(UUID.fromString(snapshot.path("usageId").asString()))
            assertThat(fact.quantityBase).isEqualTo(82500L)
        }
    }

    @Test fun `zero intermediate quantity cannot make consumed material reusable`() {
        val case = usageCase()
        used(case)
        val before = usageAccounting(case)

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            sql("UPDATE inventory_balance_projection SET status='ISSUED',quantity_base=0,revision=revision+1 WHERE status='CONSUMED' AND quantity_base>0")
            sql("UPDATE inventory_balance_projection SET quantity_base=1,revision=revision+1 WHERE status='ISSUED' AND location_id IN (SELECT id FROM inventory_location WHERE code='CONSUMED')")
            sql("SET CONSTRAINTS ALL IMMEDIATE")
        } }.hasStackTraceContaining("consumed material cannot become reusable custody")

        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @Test fun `consumed material cannot be reclassified as reusable stock`() {
        val case = usageCase()
        used(case)
        val before = usageAccounting(case)

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            sql("UPDATE inventory_balance_projection SET status='ISSUED',revision=revision+1 WHERE status='CONSUMED' AND quantity_base>0")
        } }.hasStackTraceContaining("consumed material cannot become reusable custody")

        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["header", "line", "snapshot", "fact", "delete"])
    fun `committed usage and physical facts cannot be mutated`(mode: String) {
        val case = usageCase()
        used(case)
        val before = usageAccounting(case)

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            sql(when (mode) {
                "header" -> "UPDATE inventory_material_usage SET evidence_reference='changed'"
                "line" -> "UPDATE inventory_material_usage_line SET used_base=1,residual_base=99999"
                "snapshot" -> "UPDATE inventory_usage_snapshot SET use_revision=2"
                "fact" -> "UPDATE inventory_customer_material_fact SET quantity_base=1"
                "delete" -> "DELETE FROM inventory_material_usage"
                else -> error("Unknown fixture")
            })
        } }.hasStackTraceContaining("warehouse_append_only")

        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["", "foreign"])
    fun `usage validator rejects cleared or foreign tenant after selective constraint execution`(mode: String) {
        val case = usageCase()
        val id = mapper.readTree(used(case)).path("usageId").asString()
        val scope = if (mode.isEmpty()) "" else UUID.randomUUID().toString()

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            val tenant = scalar("SELECT current_setting('app.tenant_id')")
            sql("SET CONSTRAINTS ALL IMMEDIATE")
            sql("SET LOCAL app.tenant_id='$scope'")
            sql("SELECT warehouse_assert_material_usage('$tenant','$id')")
        } }.hasStackTraceContaining("row tenant scope")
    }

    @Test fun `FORCE RLS hides usage tables and restored tenant validates original facts`() {
        val case = usageCase()
        val id = mapper.readTree(used(case)).path("usageId").asString()

        fixture(case.receipt.stock.token).transaction {
            val tenant = scalar("SELECT current_setting('app.tenant_id')")
            assertThat(scalar("""SELECT count(*) FROM pg_class WHERE relname IN ('inventory_material_usage','inventory_material_usage_line')
                AND relnamespace=current_schema()::regnamespace AND relrowsecurity AND relforcerowsecurity""")).isEqualTo("2")
            for (scope in listOf("", UUID.randomUUID().toString())) {
                sql("SET LOCAL app.tenant_id='$scope'")
                assertThat(scalar("SELECT count(*) FROM inventory_material_usage")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_material_usage_line")).isEqualTo("0")
            }
            sql("SET LOCAL app.tenant_id='$tenant'")
            sql("SELECT warehouse_assert_material_usage('$tenant','$id')")
            assertThat(scalar("SELECT count(*) FROM inventory_material_usage")).isEqualTo("1")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["normal", "cleared", "foreign", "restored", "selective"])
    fun `forged consumed projection cannot replace acknowledged custody`(mode: String) {
        val case = usageCase()
        val before = usageAccounting(case)

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            val tenant = scalar("SELECT current_setting('app.tenant_id')")
            if (mode == "selective") {
                sql("SET CONSTRAINTS ALL IMMEDIATE")
                sql("SET CONSTRAINTS warehouse_consumed_balance_bound DEFERRED")
            }
            sql("UPDATE inventory_balance_projection SET status='CONSUMED',revision=revision+1 WHERE custody_owner_kind='TECHNICIAN' AND quantity_base>0")
            when (mode) {
                "cleared", "selective" -> sql("SET LOCAL app.tenant_id=''")
                "foreign" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                "restored" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                "normal" -> Unit
            }
            sql("SET CONSTRAINTS warehouse_consumed_balance_bound IMMEDIATE")
        } }.hasStackTraceContaining(if (mode in setOf("cleared", "foreign", "selective")) "row tenant scope" else "consumed state requires physical posting")

        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @Test fun `another tenants actor cannot be linked to a usage header`() {
        val case = usageCase()
        val foreign = usageCase()

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            sql("""INSERT INTO inventory_material_usage(id,tenant_id,actor_id,evidence_reference,recorded_at)
                VALUES (gen_random_uuid(),current_setting('app.tenant_id')::uuid,'${foreign.receipt.receiver.second}','evidence',clock_timestamp())""")
        } }.hasStackTraceContaining("foreign key constraint")
    }

    @ParameterizedTest @EnumSource(value = TestPostingPhase::class, names = ["DOCUMENT", "HEADER", "LEGS", "BALANCES", "MATERIAL_FACT", "FACTS", "EVENTS"])
    fun `interrupted physical posting rolls back usage fact snapshot and outcome together`(phase: TestPostingPhase) {
        val case = usageCase()
        val before = usageAccounting(case)

        PostingJdbcProbe(context, phase) { throw IllegalStateException("task16 transactional interruption") }.use { probe ->
            assertThatThrownBy { use(case) }.hasStackTraceContaining("task16 transactional interruption")
            assertThat(probe.observations).isEqualTo(1)
        }

        assertThat(usageAccounting(case)).isEqualTo(before)
        assertThat(use(case).status).isEqualTo(200)
    }
}
