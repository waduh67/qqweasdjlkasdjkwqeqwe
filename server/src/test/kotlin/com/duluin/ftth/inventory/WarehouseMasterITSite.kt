package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID

class WarehouseMasterITSite : WarehouseMasterHttpFixture() {
    private fun siteBody(code: String, area: String) = """{"code":"$code","name":"Site","location":{"longitude":106.8,"latitude":-6.2},"areaId":"$area"}"""
    private fun site(token: String, code: String): String {
        val response = request("POST", "/api/sites", token, siteBody(code, area(token)))
        assertThat(response.status).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }

    @Test fun `AV7-02 referenced site deletion returns conflict rather than dangling master`() {
        val token = tenant(); val site = site(token, "SITE")
        val warehouse = create("locations",token,"""{"code":"WH","name":"Warehouse","kind":"WAREHOUSE","siteId":"$site"}""")
        val response = request("DELETE", "/api/sites/$site", token)
        println("AV7-02 delete status=${response.status} body=${response.contentAsString}")
        assertThat(response.status).isEqualTo(409)
        assertThat(request("GET", "/api/v1/warehouse/locations/${warehouse.path("id").asString()}", token).status).isEqualTo(200)
        assertThat(request("GET", "/api/sites/$site", token).status).isEqualTo(200)
    }

    @Test fun `AV7-02 referenced site area cannot change but unchanged area remains editable`() {
        val token = tenant(); val site = site(token, "SITE")
        create("locations",token,"""{"code":"WH","name":"Warehouse","kind":"WAREHOUSE","siteId":"$site"}""")
        val another = mapper.readTree(request("POST", "/api/areas", token, """{"code":"OTHER","name":"Other"}""").contentAsString).path("id").asString()
        val response = request("PUT", "/api/sites/$site", token, siteBody("SITE", another))
        println("AV7-02 move status=${response.status} body=${response.contentAsString}")
        assertThat(response.status).isEqualTo(409)
        assertThat(request("PUT", "/api/sites/$site", token, siteBody("SITE", area(token))).status).isEqualTo(200)
    }

    @Test fun `AV7-02 child site must match parent site on create and update`() {
        val token = tenant(); val first = site(token,"FIRST"); val second = site(token,"SECOND")
        val parent = create("locations",token,"""{"code":"WH","name":"Warehouse","kind":"WAREHOUSE","siteId":"$first"}""").path("id").asString()
        val invalid = """{"code":"BIN","name":"Bin","kind":"BIN","areaId":"${area(token)}","parentLocationId":"$parent","siteId":"$second"}"""
        assertThat(request("POST", "/api/v1/warehouse/locations", token, invalid).status).isEqualTo(400)
        val child = create("locations",token,invalid.replace(second,first)).path("id").asString()
        assertThat(request("PUT", "/api/v1/warehouse/locations/$child", token,invalid.dropLast(1)+",\"expectedRevision\":0}").status).isEqualTo(400)
    }

    @Test fun `AV7-02 database rejects missing foreign site and site area changes as app role`() {
        val token = tenant(); val site = site(token,"SITE")
        create("locations",token,"""{"code":"WH","name":"Warehouse","kind":"WAREHOUSE","siteId":"$site"}""")
        val fixture = fixture(token)
        val missing = assertThrows<RuntimeException> { fixture.transaction {
            sql("INSERT INTO inventory_location(id,tenant_id,code,kind,site_id) VALUES ('${UUID.randomUUID()}','$tenant','INVALID','WAREHOUSE','${UUID.randomUUID()}')")
        } }
        assertThat(generateSequence<Throwable>(missing) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23503")
        val changed = assertThrows<RuntimeException> { fixture.transaction { sql("UPDATE site SET area_id=NULL WHERE id='$site'") } }
        assertThat(generateSequence<Throwable>(changed) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23514")
    }

    @ParameterizedTest @ValueSource(strings = ["MOVE", "DELETE"])
    fun `AV7-02 replay authorizes original site even after master points to another site`(change: String) {
        val token = tenant(); val original = site(token,"ORIGINAL"); val replacement = site(token,"REPLACEMENT")
        val body = """{"code":"WH","name":"Warehouse","kind":"WAREHOUSE","siteId":"$original","areaId":"${area(token)}"}"""
        val key = UUID.randomUUID().toString()
        val first = request("POST", "/api/v1/warehouse/locations",token,body,key)
        assertThat(first.status).isEqualTo(201)
        val id = mapper.readTree(first.contentAsString).path("id").asString()
        assertThat(request("PUT", "/api/v1/warehouse/locations/$id",token,body.replace(original,replacement).dropLast(1)+",\"expectedRevision\":0}").status).isEqualTo(200)
        if(change=="DELETE") assertThat(request("DELETE", "/api/sites/$original",token).status).isEqualTo(204) else {
            val hidden = mapper.readTree(request("POST", "/api/areas",token,"""{"code":"HIDDEN","name":"Hidden"}""").contentAsString).path("id").asString()
            assertThat(request("PUT", "/api/sites/$original",token,siteBody("ORIGINAL",hidden)).status).isEqualTo(200)
        }
        assertThat(request("GET", "/api/v1/warehouse/locations/$id",token).status).isEqualTo(200)
        val replay = request("POST", "/api/v1/warehouse/locations",token,body,key)
        assertThat(replay.status).isEqualTo(404)
        assertThat(replay.contentAsString).doesNotContain(original,id)
    }
}
