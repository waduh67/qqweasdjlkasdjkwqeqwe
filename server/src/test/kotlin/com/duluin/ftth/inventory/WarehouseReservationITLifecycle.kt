package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.WarehouseReservationExpiry
import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseReservationITLifecycle : WarehouseReservationFixture() {
    @Test fun `continuous shortage requires explicit partial and never joins remnants`() {
        val (token, fixture) = prepare()
        receive(fixture, "10"); receive(fixture, "10")
        val demand = demand(token, fixture, 15000)
        val shortage = reserve(demand)
        assertThat(shortage.path("state").asString()).isEqualTo("SUBMITTED")
        assertThat(shortage.path("lines")[0].path("backorderBase").asString()).isEqualTo("15000")
        assertThat(allocations(demand).size()).isZero()
        val partial = reserve(demand, 2, """, "lines":[{"demandLineId":"${demand.line}","partialQuantityBase":"9000"}]""")
        assertThat(partial.path("lines")[0].path("backorderBase").asString()).isEqualTo("6000")
        val retry = reserve(demand, 3)
        assertThat(retry.path("lines")[0].path("backorderBase").asString()).isEqualTo("6000")
        assertThat(allocations(demand).size()).isEqualTo(1)
    }
    @Test fun `pick unpick release preserve one encumbrance and exact revisioned history`() {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        val demand = demand(token, fixture, 100000, false)
        reserve(demand)
        val legs = fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }
        var row = allocations(demand).single()
        assertThat(row.path("reservationRevision").asLong()).isZero()
        call(demand, "pick", mutation(2, row, "20000"))
        row = allocations(demand).single()
        assertThat(row.path("reservationRevision").asLong()).isEqualTo(1)
        assertThat(row.path("reservedUnpickedBase").asString()).isEqualTo("40000")
        assertThat(row.path("reservedPickedBase").asString()).isEqualTo("20000")
        assertThat(available(demand)).isEqualTo("0")
        assertThat(request("POST", "/api/v1/warehouse/material-requests/${demand.document}/release", token, mutation(3, row, "40000")).status).isEqualTo(409)
        call(demand, "unpick", mutation(3, row, "20000"))
        row = allocations(demand).single()
        val released = call(demand, "release", mutation(4, row, "60000"))
        assertThat(released.path("state").asString()).isEqualTo("SUBMITTED")
        assertThat(available(demand)).isEqualTo("60000")
        reserve(demand, 5)
        assertThat(allocations(demand).size()).isEqualTo(2)
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }).isEqualTo(legs)
    }
    @Test fun `expiry from two workers releases only unpicked once and explicit unpick remains required`() {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        val demand = demand(token, fixture, 60000)
        reserve(demand)
        call(demand, "pick", mutation(2, allocations(demand).single(), "20000"))
        fixture.transaction { sql("UPDATE inventory_reservation SET submitted_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 second',revision=revision+1") }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val futures = (1..2).map { pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS)
                TenantContext.runAs(fixture.tenant) { context.getBean(WarehouseReservationExpiry::class.java).expireOne() } }) }
            assertThat(futures.map { it.get(40, TimeUnit.SECONDS) }.count { it }).isEqualTo(1)
        } finally { pool.shutdownNow() }
        val row = allocations(demand).single()
        assertThat(row.path("reservedUnpickedBase").asString()).isEqualTo("0")
        assertThat(row.path("reservedPickedBase").asString()).isEqualTo("20000")
        assertThat(row.path("state").asString()).isEqualTo("OPEN")
        assertThat(available(demand)).isEqualTo("40000")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_outbox WHERE event_kind='RESERVATION_EXPIRED'") }).isEqualTo("1")
        assertThat(TenantContext.runAs(fixture.tenant) { context.getBean(WarehouseReservationExpiry::class.java).expireOne() }).isFalse()
    }
    @Test fun `last serial race never double reserves`() {
        val (token, fixture) = prepare()
        receive(fixture, "1", true)
        val demands = (1..2).map { demand(token, fixture, 1, serial = true) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val futures = demands.map { demand -> pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); reserve(demand) }) }
            val outcomes = futures.map { it.get(40, TimeUnit.SECONDS) }
            assertThat(outcomes.count { it.path("state").asString() == "RESERVED" }).isEqualTo(1)
            assertThat(outcomes.count { it.path("state").asString() == "SUBMITTED" }).isEqualTo(1)
        } finally { pool.shutdownNow() }
        assertThat(fixture.transaction { scalar("SELECT sum(reserved_unpicked_base) FROM inventory_reservation WHERE state='OPEN'") }).isEqualTo("1")
    }
    @Test fun `reallocation rolls back source on target conflict and succeeds without custody movement`() {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        val source = demand(token, fixture, 60000)
        val target = demand(token, fixture, 60000)
        reserve(source)
        val row = allocations(source).single()
        val targetBody = """, "target":{"documentId":"${target.document}","expectedRevision":1,"workOrderRevision":0,"planRevision":1,"demandLineId":"${target.line}"}"""
        val bad = mutation(2, row, "60000", targetBody.replace("\"planRevision\":1", "\"planRevision\":2"))
        val before = fixture.transaction { counts() }
        assertThat(request("POST", "/api/v1/warehouse/material-requests/${source.document}/reallocate", token, bad).status).isEqualTo(409)
        assertThat(fixture.transaction { counts() }).isEqualTo(before)
        val legs = fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }
        call(source, "reallocate", mutation(2, row, "60000", targetBody), "move-job")
        assertThat(allocations(source).single().path("state").asString()).isEqualTo("RELEASED")
        assertThat(allocations(target).single().path("reservedUnpickedBase").asString()).isEqualTo("60000")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }).isEqualTo(legs)
        call(source, "reallocate", mutation(2, row, "60000", targetBody), "move-job")
        assertThat(allocations(target).size()).isEqualTo(1)
    }
}
