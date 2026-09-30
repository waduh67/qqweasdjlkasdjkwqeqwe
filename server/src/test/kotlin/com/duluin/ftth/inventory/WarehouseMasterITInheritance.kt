package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WarehouseMasterITInheritance : WarehouseInheritanceFixture() {
    @Test fun `AV7-02 committed four-level A null B null chain is rejected`() {
        val token = tenant(); val first = site(token,"A"); val second = site(token,"B")
        val root = location(token,"ROOT",null,first); val bridge = location(token,"BRIDGE",root,null)
        val conflict = UUID.randomUUID(); val leaf = UUID.randomUUID()
        val fixture = fixture(token)
        val failure = assertThrows<RuntimeException> { fixture.transaction {
            insertLocation(conflict,bridge,second,area(token))
            insertLocation(leaf,conflict.toString(),null,area(token))
        } }
        assertThat(sqlState(failure)).isEqualTo("23514")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_location WHERE id IN ('$conflict','$leaf')") }).isEqualTo("0")
    }

    @Test fun `AV7-02 HTTP rejects conflicting site inherited through null parent with409`() {
        val token = tenant(); val first = site(token,"A"); val second = site(token,"B")
        val root = location(token,"ROOT",null,first); val bridge = location(token,"BRIDGE",root,null)
        val response = request("POST", "/api/v1/warehouse/locations",token,locationBody(token,"CONFLICT",bridge,second))
        println("INHERITANCE HTTP A-null-B status=${response.status}")
        assertThat(response.status).isEqualTo(409)
    }

    @ParameterizedTest @ValueSource(strings=["NULL,NULL,NULL", "A,NULL,NULL", "NULL,A,NULL", "A,NULL,A"])
    fun `valid effective site inheritance stays readable`(chain: String) {
        val token = tenant(); val site = site(token,"A")
        var parent: String? = null
        chain.split(',').forEachIndexed { index, value ->
            val body = locationBody(token,"LEVEL-$index",parent,if(value=="NULL") null else site)
            val key = UUID.randomUUID().toString()
            val created = request("POST", "/api/v1/warehouse/locations",token,body,key)
            assertThat(created.status).isEqualTo(201)
            parent = mapper.readTree(created.contentAsString).path("id").asString()
            assertThat(request("GET", "/api/v1/warehouse/locations/$parent",token).status).isEqualTo(200)
            assertThat(request("POST", "/api/v1/warehouse/locations",token,body,key).contentAsString).isEqualTo(created.contentAsString)
        }
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/locations?search=LEVEL",token).contentAsString).path("totalElements").asInt()).isEqualTo(3)
    }

    @ParameterizedTest @ValueSource(strings=["SITE", "REPARENT", "CYCLE", "AREA"])
    fun `ancestor changes cannot invalidate existing descendant chains`(change: String) {
        val token = tenant(); val first = site(token,"A"); val second = site(token,"B")
        val root = location(token,"ROOT",null,null)
        val bridge = location(token,"BRIDGE",root,null)
        val leaf = location(token,"LEAF",bridge,if(change=="CYCLE") null else second)
        val destination = location(token,"DESTINATION",null,first)
        val query = when(change) {
            "SITE" -> "UPDATE inventory_location SET site_id='$first',revision=revision+1 WHERE id='$root'"
            "REPARENT" -> "UPDATE inventory_location SET parent_location_id='$destination',revision=revision+1 WHERE id='$root'"
            "AREA" -> {
                val other=mapper.readTree(request("POST","/api/areas",token,"""{"code":"OTHER","name":"Other"}""").contentAsString).path("id").asString()
                "UPDATE inventory_location SET area_id='$other',revision=revision+1 WHERE id='$root'"
            }
            else -> "UPDATE inventory_location SET parent_location_id='$leaf',revision=revision+1 WHERE id='$root'"
        }
        val failure = assertThrows<RuntimeException> { fixture(token).transaction { sql(query) } }
        assertThat(sqlState(failure)).isEqualTo("23514")
    }
}
