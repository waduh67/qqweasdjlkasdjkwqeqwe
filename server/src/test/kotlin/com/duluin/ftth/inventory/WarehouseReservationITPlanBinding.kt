package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.WarehouseReservationExpiry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseReservationITPlanBinding : WarehouseReservationFixture() {
    private fun picked(): Demand {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        val demand = demand(token, fixture, 100000, false)
        reserve(demand, key = "original-reserve")
        call(demand, "pick", mutation(2, allocations(demand).single(), "20000"))
        return demand
    }
    private fun newerDraft(demand: Demand) = demand.fixture.transaction {
        val plan = UUID.randomUUID()
        sql("""INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id)
            SELECT '$plan',tenant_id,work_order_id,2,work_order_revision,material_mode,actor_id FROM inventory_material_plan WHERE work_order_id='${demand.workOrder}' AND plan_revision=1""")
        sql("""INSERT INTO inventory_material_plan_line(id,tenant_id,plan_id,line_number,sku_id,quantity_base,base_unit,continuous_cut)
            SELECT gen_random_uuid(),tenant_id,'$plan',line_number,sku_id,quantity_base,base_unit,continuous_cut FROM inventory_material_plan_line
            WHERE plan_id=(SELECT id FROM inventory_material_plan WHERE work_order_id='${demand.workOrder}' AND plan_revision=1)""")
    }

    @Test fun `newer draft does not hide revision bound picked allocation history`() {
        val demand = picked()
        val before = allocations(demand)
        newerDraft(demand)
        assertThat(allocations(demand)).isEqualTo(before)
        authenticated(demand.token, demand.fixture) {
            val allocation = context.getBean(InventoryApi::class.java).fulfillmentAllocations(demand.workOrder).single().reservation!!
            assertThat(allocation.planRevision).isEqualTo(1)
            assertThat(allocation.reservedPickedBase).isEqualTo("20000")
        }
    }
    @Test fun `newer draft allows original replay but rejects new allocations from older plan`() {
        val demand = picked()
        val original = reserve(demand, key = "original-reserve")
        newerDraft(demand)
        assertThat(reserve(demand, key = "original-reserve")).isEqualTo(original)
        val before = demand.fixture.transaction { counts() }
        val rejected = request("POST", "/api/v1/warehouse/material-requests/${demand.document}/reserve", demand.token,
            """{"expectedRevision":3,"workOrderRevision":0,"planRevision":1}""")
        assertThat(rejected.status).isEqualTo(409)
        assertThat(rejected.contentAsString).contains("SOURCE_NOT_VERIFIED")
        assertThat(demand.fixture.transaction { counts() }).isEqualTo(before)
    }
    @Test fun `newer draft allows explicit unpick and exact release without physical movement`() {
        val demand = picked()
        val row = allocations(demand).single()
        val legs = demand.fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }
        newerDraft(demand)
        call(demand, "unpick", mutation(3, row, "20000"))
        val unpicked = allocations(demand).single()
        call(demand, "release", mutation(4, unpicked, "60000"))
        assertThat(allocations(demand).single().path("state").asString()).isEqualTo("RELEASED")
        assertThat(available(demand)).isEqualTo("60000")
        assertThat(demand.fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }).isEqualTo(legs)
    }
    @Test fun `newer draft cannot prevent due expiry and remaining picked unpick`() {
        val demand = picked()
        newerDraft(demand)
        demand.fixture.transaction { sql("UPDATE inventory_reservation SET submitted_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 second',revision=revision+1") }
        assertThat(TenantContext.runAs(demand.fixture.tenant) { context.getBean(WarehouseReservationExpiry::class.java).expireOne() }).isTrue()
        val row = allocations(demand).single()
        assertThat(row.path("reservedPickedBase").asString()).isEqualTo("20000")
        assertThat(row.path("reservedUnpickedBase").asString()).isEqualTo("0")
        call(demand, "unpick", mutation(4, row, "20000"))
        call(demand, "release", mutation(5, allocations(demand).single(), "20000"))
    }

    @Test fun `historical binding still enforces current scope and permission after newer draft`() {
        val demand = picked()
        val row = allocations(demand).single()
        newerDraft(demand)
        val actor = UUID.fromString(mapper.readTree(request("GET", "/api/me", demand.token).contentAsString).path("id").asString())
        val scopeApi = context.getBean(com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseScopePersistence::class.java)
        val manager = user(demand.token, setOf("inventory.location.manage"))
        authenticated(demand.token, demand.fixture) { scopeApi.replace(UUID.fromString(manager.second), setOf(demand.fixture.warehouse), 0) }
        authenticated(demand.token, demand.fixture) { scopeApi.replace(actor, emptySet(), 0) }
        val before = demand.fixture.transaction { counts() }
        val path = "/api/v1/warehouse/material-requests/${demand.document}"
        assertThat(request("GET", "/api/v1/warehouse/material-requests/allocations/${demand.workOrder}", demand.token).status).isEqualTo(404)
        assertThat(request("POST", "$path/unpick", demand.token, mutation(3, row, "20000")).status).isEqualTo(404)
        assertThat(request("POST", "$path/reserve", demand.token, """{"expectedRevision":1,"workOrderRevision":0,"planRevision":1}""", "original-reserve").status).isEqualTo(404)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            authenticated(demand.token, demand.fixture) { scopeApi.replace(actor, setOf(demand.fixture.warehouse), 0) }
        }.isInstanceOf(WarehouseContractException::class.java)
        authenticated(manager.first, demand.fixture) { scopeApi.replace(actor, setOf(demand.fixture.warehouse), 0) }
        assertThat(allocations(demand).single()).isEqualTo(row)
        assertThat(request("PUT", "/api/users/$actor/access", demand.token, """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
        assertThat(request("GET", "/api/v1/warehouse/material-requests/allocations/${demand.workOrder}", demand.token).status).isEqualTo(403)
        assertThat(request("POST", "$path/unpick", demand.token, mutation(3, row, "20000")).status).isEqualTo(403)
        assertThat(request("POST", "$path/reserve", demand.token, """{"expectedRevision":1,"workOrderRevision":0,"planRevision":1}""", "original-reserve").status).isEqualTo(403)
        assertThat(demand.fixture.transaction { counts() }).isEqualTo(before)
    }

    @Test fun `reallocation uses historical source binding but requires current target plan`() {
        val source = picked()
        newerDraft(source)
        call(source, "unpick", mutation(3, allocations(source).single(), "20000"))
        val staleTarget = demand(source.token, source.fixture, 60000)
        newerDraft(staleTarget)
        val row = allocations(source).single()
        fun targetBody(target: Demand) = """, "target":{"documentId":"${target.document}","expectedRevision":1,"workOrderRevision":0,"planRevision":1,"demandLineId":"${target.line}"}"""
        val before = source.fixture.transaction { counts() }
        assertThat(request("POST", "/api/v1/warehouse/material-requests/${source.document}/reallocate", source.token,
            mutation(4, row, "60000", targetBody(staleTarget))).status).isEqualTo(409)
        assertThat(source.fixture.transaction { counts() }).isEqualTo(before)
        val target = demand(source.token, source.fixture, 60000)
        call(source, "reallocate", mutation(4, row, "60000", targetBody(target)))
        assertThat(allocations(source).single().path("state").asString()).isEqualTo("RELEASED")
        assertThat(allocations(target).single().path("reservedUnpickedBase").asString()).isEqualTo("60000")
    }
}
