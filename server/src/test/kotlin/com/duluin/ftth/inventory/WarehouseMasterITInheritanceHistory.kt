package com.duluin.ftth.inventory

import org.assertj.core.api.SoftAssertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseMasterITInheritanceHistory : WarehouseInheritanceFixture() {
    companion object {
        private val history=WarehouseSiteHistorySeed(true)
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { history.database.url }; registry.add("spring.flyway.url") { history.database.url }
            registry.add("spring.flyway.schemas") { history.database.schema }; registry.add("spring.flyway.default-schema") { history.database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { history.close() }
    }
    @Test fun `historical A null B null leaf is preserved but hidden in detail search replay and lookup`() {
        val token=tenant(history.slug); val fixture=fixture(token)
        val me=mapper.readTree(request("GET","/api/me",token).contentAsString)
        assertThat(request("PUT","/api/users/${me.path("id").asString()}/access",token,mapper.writeValueAsString(mapOf(
            "roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(),"areaIds" to listOf(history.area.toString())))).status).isEqualTo(200)
        val (body,key)=history.recordReplay(fixture,UUID.fromString(me.path("id").asString()))
        val assertions=SoftAssertions()
        for(id in listOf(history.conflict,history.leaf)) {
            val detail=request("GET","/api/v1/warehouse/locations/$id",token)
            println("INHERITANCE historical id=$id detail=${detail.status}")
            assertions.assertThat(detail.status).isEqualTo(404)
        }
        val page=request("GET","/api/v1/warehouse/locations?search=TRANSITIVE",token)
        assertions.assertThat(mapper.readTree(page.contentAsString).path("totalElements").asInt()).isZero()
        assertions.assertThat(request("POST","/api/v1/warehouse/locations",token,body,key).status).isEqualTo(404)
        assertions.assertThat(request("GET","/api/v1/warehouse/assets/lookup?value=TRANSITIVE-ASSET",token).status).isEqualTo(404)
        assertions.assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_location WHERE id IN ('${history.conflict}','${history.leaf}')") }).isEqualTo("2")
        assertions.assertAll()
    }
}
