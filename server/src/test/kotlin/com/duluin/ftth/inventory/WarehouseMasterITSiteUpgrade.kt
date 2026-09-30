package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseMasterITSiteUpgrade : WarehouseMasterHttpFixture() {
    companion object {
        private val history = WarehouseSiteHistorySeed(false)
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { history.database.url }
            registry.add("spring.flyway.url") { history.database.url }
            registry.add("spring.flyway.schemas") { history.database.schema }
            registry.add("spring.flyway.default-schema") { history.database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { history.close() }
    }

    @Test fun `AV7-02 safe upgrade preserves invalid history but hides detail list and replay`() {
        val token = tenant(history.slug); val fixture = fixture(token)
        val me = mapper.readTree(request("GET", "/api/me",token).contentAsString)
        assertThat(request("PUT", "/api/users/${me.path("id").asString()}/access",token,mapper.writeValueAsString(mapOf(
            "roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(),"areaIds" to listOf(history.area.toString())))).status).isEqualTo(200)
        val (body,key) = history.recordReplay(fixture,UUID.fromString(me.path("id").asString()))
        val id = history.target.toString(); val site = history.firstSite.toString()
        val detail = request("GET", "/api/v1/warehouse/locations/$id",token)
        val replay = request("POST", "/api/v1/warehouse/locations",token,body,key)
        val page = request("GET", "/api/v1/warehouse/locations?search=WH",token)
        println("AV7-02 historical site move detail=${detail.status} replay=${replay.status} page=${page.contentAsString}")
        assertThat(detail.status).isEqualTo(404)
        assertThat(replay.status).isEqualTo(404)
        assertThat(replay.contentAsString).doesNotContain(id)
        assertThat(mapper.readTree(page.contentAsString).path("totalElements").asLong()).isZero()
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_location WHERE id='$id' AND site_id='$site'") }).isEqualTo("1")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_operation WHERE operation_key='$key'") }).isEqualTo("1")
    }
}
