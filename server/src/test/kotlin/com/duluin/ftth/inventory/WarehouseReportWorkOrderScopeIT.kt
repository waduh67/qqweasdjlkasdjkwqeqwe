package com.duluin.ftth.inventory

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.customer.CustomerAssetOwnershipFixture
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WarehouseReportWorkOrderScopeIT : CustomerAssetOwnershipFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["LOAN", "SALE"])
    fun `current work order area controls assignments costs and historical print with warehouse access retained`(mode: String) {
        val case = ownershipCase(mode)
        assertThat(accept(case).status).isEqualTo(200)
        val receipt = case.installation.receipt
        val admin = receipt.stock.token
        val secondArea = addArea(admin)
        grantAreas(admin, admin, listOf(area(admin), secondArea))
        val (viewer, userId) = user(admin, setOf("inventory.report.view", "inventory.cost.view"))
        grantAreas(admin, viewer, listOf(area(admin)))
        val locations = fixture(admin).transaction {
            scalar("SELECT string_agg(id::text,',' ORDER BY id) FROM inventory_location")
        }.split(',')
        for (location in locations) {
            val response = request("PUT", "/api/v1/warehouse/settings/scopes/$userId/$location", admin,
                """{"expectedRevision":0,"active":true}""")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        }
        val assignments = if (mode == "LOAN") "loan-assets" else "sold-assets"
        val costs = "work-order-costs?workOrderId=${receipt.workOrder}"
        val print = "documents/${receipt.input.issueId}/revisions/2/print"
        val original = listOf(assignments, costs, print).associateWith { path ->
            val response = request("GET", "/api/v1/warehouse/reports/$path", viewer)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            response.contentAsString
        }
        assertThat(mapper.readTree(original.getValue(assignments)).path("items").size()).isEqualTo(1)
        assertThat(mapper.readTree(original.getValue(costs)).path("items").size()).isEqualTo(1)
        val before = fixture(admin).transaction { counts() }

        fun moveWorkOrder(areaId: String) {
            val response = request("PUT", "/api/work-orders/${receipt.workOrder}", admin,
                mapper.writeValueAsString(mapOf("title" to "Report scope", "areaId" to areaId,
                    "customerId" to case.installation.customer)))
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        }
        moveWorkOrder(secondArea)
        for (path in listOf(assignments, costs)) {
            val hidden = request("GET", "/api/v1/warehouse/reports/$path", viewer)
            assertThat(hidden.status).withFailMessage(hidden.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(hidden.contentAsString).path("totalElements").asInt()).isZero()
        }
        assertThat(request("GET", "/api/v1/warehouse/reports/$print", viewer).status).isEqualTo(404)
        val stock = request("GET", "/api/v1/warehouse/reports/stock", viewer)
        assertThat(stock.status).isEqualTo(200)
        assertThat(mapper.readTree(stock.contentAsString).path("totalElements").asInt()).isPositive()
        for (path in original.keys) assertThat(request("GET", "/api/v1/warehouse/reports/$path", admin).status).isEqualTo(200)

        moveWorkOrder(area(admin))
        original.forEach { (path, body) ->
            val restored = request("GET", "/api/v1/warehouse/reports/$path", viewer)
            assertThat(restored.status).withFailMessage(restored.contentAsString).isEqualTo(200)
            assertThat(restored.contentAsString).isEqualTo(body)
        }
        assertThat(fixture(admin).transaction { counts() }).isEqualTo(before)
    }

    @Test
    fun `owner read port distinguishes unrestricted empty foreign area and foreign tenant scopes`() {
        val admin = tenant()
        val secondArea = addArea(admin)
        grantAreas(admin, admin, listOf(area(admin), secondArea))
        fun createWork(token: String, areaId: String): UUID {
            val response = request("POST", "/api/work-orders", token,
                """{"type":"PREVENTIVE","title":"Owner scope","areaId":"$areaId"}""")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            return UUID.fromString(mapper.readTree(response.contentAsString).path("id").asString())
        }
        val first = createWork(admin, area(admin))
        val second = createWork(admin, secondArea)
        val foreign = tenant()
        val other = createWork(foreign, area(foreign))
        val port = context.getBean(InventoryWorkOrderReadPort::class.java)
        fun visible(token: String, scope: AuthorityScope) = fixture(token).transaction { port.visibleWorkOrderIds(scope) }
        assertThat(visible(admin, AuthorityScope.Unrestricted)).containsExactlyInAnyOrder(first, second)
        assertThat(visible(admin, AuthorityScope.Restricted(setOf(UUID.fromString(area(admin)))))).containsExactly(first)
        assertThat(visible(admin, AuthorityScope.Restricted(setOf(UUID.fromString(secondArea))))).containsExactly(second)
        assertThat(visible(admin, AuthorityScope.Restricted(emptySet()))).isEmpty()
        assertThat(visible(admin, AuthorityScope.Restricted(setOf(UUID.fromString(area(foreign)))))).isEmpty()
        assertThat(visible(foreign, AuthorityScope.Unrestricted)).containsExactly(other)
    }

    @Test
    fun `an area change waits for the owner scoped report transaction to finish`() {
        val admin = tenant()
        val secondArea = addArea(admin)
        grantAreas(admin, admin, listOf(area(admin), secondArea))
        val work = UUID.fromString(workOrder(admin))
        val port = context.getBean(InventoryWorkOrderReadPort::class.java)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reading = workers.submit {
                fixture(admin).transaction {
                    backend.set(scalar("SELECT pg_backend_pid()").toInt())
                    assertThat(port.visibleWorkOrderIds(AuthorityScope.Restricted(setOf(UUID.fromString(area(admin)))))).contains(work)
                    held.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
            }
            check(held.await(20, TimeUnit.SECONDS))
            val changing = workers.submit<org.springframework.mock.web.MockHttpServletResponse> {
                request("PUT", "/api/work-orders/$work", admin,
                    """{"title":"Moved after report","areaId":"$secondArea"}""")
            }
            await().atMost(Duration.ofSeconds(10)).until {
                fixture(admin).transaction {
                    scalar("SELECT count(*) FROM pg_stat_activity WHERE ${backend.get()}=ANY(pg_blocking_pids(pid))").toLong() > 0
                }
            }
            assertThat(changing.isDone).isFalse()
            release.countDown()
            reading.get(10, TimeUnit.SECONDS)
            val changed = changing.get(10, TimeUnit.SECONDS)
            assertThat(changed.status).withFailMessage(changed.contentAsString).isEqualTo(200)
            assertThat(fixture(admin).transaction {
                port.visibleWorkOrderIds(AuthorityScope.Restricted(setOf(UUID.fromString(area(admin)))))
            }).doesNotContain(work)
            assertThat(fixture(admin).transaction {
                port.visibleWorkOrderIds(AuthorityScope.Restricted(setOf(UUID.fromString(secondArea))))
            }).contains(work)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    private fun addArea(admin: String): String {
        val response = request("POST", "/api/areas", admin, """{"code":"SECOND","name":"Second area"}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }

    private fun grantAreas(admin: String, token: String, areaIds: List<String>) {
        val me = mapper.readTree(request("GET", "/api/me", token).contentAsString)
        val response = request("PUT", "/api/users/${me.path("id").asString()}/access", admin,
            mapper.writeValueAsString(mapOf("roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to areaIds)))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }
}
