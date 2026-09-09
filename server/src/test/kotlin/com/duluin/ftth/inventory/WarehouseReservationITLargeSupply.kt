package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.WarehouseReservationExpiry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseReservationITLargeSupply : WarehouseReservationFixture() {
    private fun receiveSerials(setup: Setup, count: Int) {
        val prefix = UUID.randomUUID().toString()
        val serials = (1..count).joinToString(",") { """{"serial":"$prefix-$it"}""" }
        val receipt = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"$count","serials":[$serials]}""")
        val id = receipt.path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val detail = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString)
        val lines = detail.path("lines").asSequence().map { line -> mapOf("lineId" to line.path("id").asString(),
            "stockIdentityId" to line.path("pieces")[0].path("stockIdentityId").asString(), "quantityBase" to "1", "baseUnit" to "EA") }.toList()
        transition(setup, id, "putaway", mapper.writeValueAsString(mapOf("expectedRevision" to 1, "destinationLocationId" to setup.bin, "lines" to lines)))
    }

    @Test fun `2001 real receipt identities cannot strand a bound serial or its original replay`() {
        val setup = setupReceipt()
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.onu}", setup.token,
            """{"expectedRevision":0,"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false}""").status).isEqualTo(200)
        val fixture = fixture(setup.token)
        receiveSerials(setup, 1)
        val first = demand(setup.token, fixture, 1, serial = true, skuId = UUID.fromString(setup.onu))
        val original = reserve(first, key = "before-growth")
        call(first, "pick", mutation(2, allocations(first).single(), "1"))
        val picked = allocations(first).single()
        repeat(4) { receiveSerials(setup, 500) }
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_segment WHERE sku_id='${setup.onu}' AND state='ACTIVE'") }).isEqualTo("2001")
        val legs = fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }
        val replay = request("POST", "/api/v1/warehouse/material-requests/${first.document}/reserve", setup.token,
            """{"expectedRevision":1,"workOrderRevision":0,"planRevision":1}""", "before-growth")
        val unpick = request("POST", "/api/v1/warehouse/material-requests/${first.document}/unpick", setup.token, mutation(3, picked, "1"))
        assertThat(listOf(replay.status, unpick.status)).withFailMessage("replay=${replay.contentAsString}; unpick=${unpick.contentAsString}").containsExactly(200, 200)
        assertThat(mapper.readTree(replay.contentAsString)).isEqualTo(original)
        call(first, "release", mutation(4, allocations(first).single(), "1"))
        val next = demand(setup.token, fixture, 1, serial = true, skuId = UUID.fromString(setup.onu))
        assertThat(reserve(next).path("state").asString()).isEqualTo("RESERVED")
        fixture.transaction { sql("UPDATE inventory_reservation SET submitted_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 second',revision=revision+1 WHERE state='OPEN'") }
        assertThat(TenantContext.runAs(fixture.tenant) { context.getBean(WarehouseReservationExpiry::class.java).expireOne() }).isTrue()
        assertThat(allocations(next).single().path("state").asString()).isEqualTo("EXPIRED")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }).isEqualTo(legs)
    }
}
