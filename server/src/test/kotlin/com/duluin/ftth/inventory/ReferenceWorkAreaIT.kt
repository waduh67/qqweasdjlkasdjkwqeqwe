package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class ReferenceWorkAreaIT : WarehouseMasterHttpFixture() {
    private fun areas(token: String, suffix: String = ""): JsonNode {
        val response = request("GET", "/api/v2/work-orders/areas$suffix", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    @Test fun `operator area choices filter scope before pagination and retain named selection`() {
        val owner = tenant()
        val ids = (0..30).map { index ->
            val response = request("POST", "/api/areas", owner,
                """{"code":"AREA-$index","name":"Zone ${index.toString().padStart(2, '0')}"}""")
            assertThat(response.status).isEqualTo(201)
            mapper.readTree(response.contentAsString).path("id").asString()
        }
        val (operator, userId) = user(owner, setOf("workorder.order.create"))
        val roles = mapper.readTree(request("GET", "/api/users/$userId", owner).contentAsString).path("roleIds")
        val selected = ids.takeLast(27)
        val granted = request("PUT", "/api/users/$userId/access", owner,
            mapper.writeValueAsString(mapOf("roleIds" to roles, "areaIds" to selected)))
        assertThat(granted.status).withFailMessage(granted.contentAsString).isEqualTo(200)
        assertThat(request("GET", "/api/areas", operator).status).isEqualTo(403)

        val first = areas(operator, "?query=zone&size=25")
        val second = areas(operator, "?query=zone&size=25&page=1")

        assertThat(first.path("totalElements").asLong()).isEqualTo(27)
        assertThat(first.path("content")).hasSize(25)
        assertThat(second.path("content")).hasSize(2)
        assertThat((first.path("content") + second.path("content")).map { it.path("id").asString() })
            .containsExactlyElementsOf(selected)
        assertThat(areas(operator, "/${selected.last()}").path("name").asString()).isEqualTo("Zone 30")
        assertThat(request("GET", "/api/v2/work-orders/areas/${ids.first()}", operator).status).isEqualTo(404)
        assertThat(areas(operator, "?query=%").path("content")).isEmpty()
        assertThat(areas(operator, "?query=_").path("content")).isEmpty()
        val foreign = tenant()
        assertThat(request("GET", "/api/v2/work-orders/areas/${area(foreign)}", operator).status).isEqualTo(404)
        assertThat(request("PUT", "/api/users/$userId/access", owner, """{"roleIds":[]}""").status).isEqualTo(200)
        assertThat(request("GET", "/api/v2/work-orders/areas", operator).status).isEqualTo(403)
    }

    @Test fun `area choices require an operator grant and valid paging`() {
        val owner = tenant()
        val (viewer, _) = user(owner, setOf("workorder.order.view"))
        assertThat(request("GET", "/api/v2/work-orders/areas", viewer).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/work-orders/areas", null).status).isEqualTo(401)
        for (suffix in listOf("?page=-1", "?size=0", "?size=201", "?query=" + "a".repeat(201)))
            assertThat(request("GET", "/api/v2/work-orders/areas$suffix", owner).status).isEqualTo(400)
    }
}
