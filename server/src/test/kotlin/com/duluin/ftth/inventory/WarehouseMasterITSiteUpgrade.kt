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
        private val database = WarehouseSchemaDatabase("174.9")
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
            registry.add("spring.flyway.target") { "174.9" }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }

    @Test fun `AV7-02 safe upgrade preserves invalid history but hides detail list and replay`() {
        val token = tenant(); val fixture = fixture(token)
        val siteBody = """{"code":"SITE","name":"Site","location":{"longitude":106.8,"latitude":-6.2},"areaId":"${area(token)}"}"""
        val site = mapper.readTree(request("POST", "/api/sites", token,siteBody).contentAsString).path("id").asString()
        val hidden = mapper.readTree(request("POST", "/api/areas",token,"""{"code":"HIDDEN","name":"Hidden"}""").contentAsString).path("id").asString()
        val body = """{"code":"WH","name":"Warehouse","kind":"WAREHOUSE","siteId":"$site","areaId":"${area(token)}"}"""
        val key = UUID.randomUUID().toString()
        val first = request("POST", "/api/v1/warehouse/locations",token,body,key)
        assertThat(first.status).isEqualTo(201)
        val id = mapper.readTree(first.contentAsString).path("id").asString()
        fixture.transaction { sql("UPDATE site SET area_id='$hidden' WHERE id='$site'") }
        database.migrate()
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
