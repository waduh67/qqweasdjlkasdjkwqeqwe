package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseFulfillmentITBngLineageUpgrade : BngHandoffFixture() {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private lateinit var preLifecycleWriter: com.duluin.ftth.fulfillment.application.service.MaterialSettlementService
    companion object {
        private val database by lazy { WarehouseSchemaDatabase("175.34") }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
            registry.add("spring.flyway.target") { "175.34" }
        }
    }
    @AfterAll fun closeDatabase() { database.close() }

    @Test fun `upgrade discovers unlisted action references under every tenant timing without rewriting history`() {
        val scopes=listOf("normal","cleared","mismatched","restored","selective")
        val cases=scopes.map {
            val case=completedHandoff()
            val extra=unbound(case)
            fixture(case.token).transaction {
                attach(case,extra)
                sql("UPDATE bng_action SET detail='old unlisted action' WHERE id='$extra'")
            }
            assertThat(request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}").status).isEqualTo(409)
            Triple(case,extra,graph(case))
        }

        assertThat(database.migrate().migrationsExecuted).isEqualTo(13)

        cases.zip(scopes).forEach { (entry,scope) ->
            val (case,extra,before)=entry
            assertThatThrownBy { fixture(case.token).transaction {
                if (scope=="selective") sql("SET CONSTRAINTS ALL IMMEDIATE; SET CONSTRAINTS warehouse_fulfillment_owner_bound DEFERRED")
                sql("UPDATE bng_action SET detail='must roll back' WHERE id='$extra'")
                when(scope) {
                    "cleared","selective" -> sql("SET LOCAL app.tenant_id=''")
                    "mismatched" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    "restored" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                    "normal" -> Unit
                    else -> error("Unknown timing")
                }
                sql("SET CONSTRAINTS warehouse_fulfillment_owner_bound IMMEDIATE")
            } }.hasStackTraceContaining(if(scope in setOf("normal","restored")) "FULFILLMENT_BNG_HANDOFF_BINDINGS" else "row tenant scope")
            assertThat(graph(case)).isEqualTo(before)
            fixture(case.token).transaction { assertThat(scalar("SELECT detail FROM bng_action WHERE id='$extra'")).isEqualTo("old unlisted action") }
            assertThat(request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}").status).isEqualTo(409)
            assertThatThrownBy { fixture(case.token).transaction {
                sql("SELECT warehouse_assert_fulfillment_owners('$tenant','${case.approvalId}')")
            } }.hasStackTraceContaining("FULFILLMENT_BNG_HANDOFF_BINDINGS")
        }
        assertThat(database.migrate().migrationsExecuted).isZero()
    }
}
