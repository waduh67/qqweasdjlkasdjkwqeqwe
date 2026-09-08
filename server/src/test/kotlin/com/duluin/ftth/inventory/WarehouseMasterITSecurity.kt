package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseMasterITSecurity : WarehouseMasterHttpFixture() {
    @Test fun `anonymous and read only users cannot mutate and revoked actor cannot recover receipt`() {
        val admin = tenant()
        val body = """{"code":"SUP-1","name":"Supplier"}"""
        assertThat(request("POST", "/api/v1/warehouse/suppliers", null, body).status).isEqualTo(401)
        val (viewer, _) = user(admin, setOf("inventory.receipt.view", "inventory.sku.view", "inventory.location.view"))
        assertThat(request("GET", "/api/v1/warehouse/suppliers", viewer).status).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/suppliers", viewer, body).status).isEqualTo(403)
        val (manager, managerId) = user(admin, setOf("inventory.receipt.view", "inventory.receipt.manage"))
        val key = UUID.randomUUID().toString()
        val first = request("POST", "/api/v1/warehouse/suppliers", manager, body, key)
        assertThat(first.status).isEqualTo(201)
        assertThat(request("POST", "/api/v1/warehouse/suppliers", admin, body, key).status).isEqualTo(403)
        assertThat(request("POST", "/api/users/$managerId/disable", admin).status).isEqualTo(200)
        val replay = request("POST", "/api/v1/warehouse/suppliers", manager, body, key)
        assertThat(replay.status).isEqualTo(403)
        assertThat(replay.contentAsString).doesNotContain("SUP-1", "Supplier")
    }

    @Test fun `foreign parent custodian and area are inaccessible and self cycle is rejected`() {
        val token = tenant()
        val foreign = create("locations", tenant(), """{"code":"FOREIGN","name":"Private","kind":"WAREHOUSE"}""").path("id").asString()
        val own = create("locations", token, """{"code":"OWN","name":"Own","kind":"WAREHOUSE"}""").path("id").asString()
        assertThat(request("GET", "/api/v1/warehouse/locations/$foreign", token).status).isEqualTo(404)
        assertThat(request("POST", "/api/v1/warehouse/locations", token,
            """{"code":"BAD","name":"Bad","kind":"BIN","parentLocationId":"$foreign"}""").status).isEqualTo(404)
        assertThat(request("POST", "/api/v1/warehouse/locations", token,
            """{"code":"BAD","name":"Bad","kind":"TECHNICIAN","custodianId":"${UUID.randomUUID()}"}""").status).isEqualTo(404)
        assertThat(request("POST", "/api/v1/warehouse/locations", token,
            """{"code":"BAD","name":"Bad","kind":"WAREHOUSE","areaId":"${UUID.randomUUID()}"}""").status).isEqualTo(404)
        assertThat(request("PUT", "/api/v1/warehouse/locations/$own", token,
            """{"code":"OWN","name":"Own","kind":"WAREHOUSE","parentLocationId":"$own","areaId":"${area(token)}","expectedRevision":0}""").status).isEqualTo(400)
        val (viewer, _) = user(token, setOf("inventory.location.view", "inventory.item.view"))
        assertThat(request("GET", "/api/v1/warehouse/locations/$own", viewer).status).isEqualTo(404)
        val page = mapper.readTree(request("GET", "/api/v1/warehouse/locations", viewer).contentAsString)
        assertThat(page.path("totalElements").asLong()).isZero()
        val absent = request("GET", "/api/v1/warehouse/assets/lookup?value=ABSENT", viewer)
        assertThat(absent.status).isEqualTo(404)
        assertThat(absent.contentAsString).contains("NOT_FOUND")
    }

    @Test fun `strict decoder rejects authority unknown fields numeric enums malformed and invalid units`() {
        val token = tenant()
        val valid = """{"code":"ONU-1","name":"ONU","tracking":"SERIAL","baseUnit":"EA"}"""
        for (field in listOf("tenantId", "actorId", "permissions", "state", "canonicalSerial", "authorityEpoch", "payloadHash", "unknown")) {
            assertThat(request("POST", "/api/v1/warehouse/skus", token, valid.dropLast(1) + ",\"$field\":\"bad\"}").status).describedAs(field).isEqualTo(400)
        }
        for (body in listOf("{", valid + "{}", valid.replace("\"SERIAL\"", "0"), valid.replace("SERIAL", "serial"),
            valid.replace("EA", "MM"), valid.replace("ONU-1", "ÜNU"), valid.dropLast(1) + ",\"minimumQuantityBase\":\"9223372036854775808\"}",
            valid.dropLast(1) + ",\"minimumQuantityBase\":\"1.5\"}")) {
            assertThat(request("POST", "/api/v1/warehouse/skus", token, body).status).describedAs(body).isEqualTo(400)
        }
        assertThat(request("POST", "/api/v1/warehouse/locations", token,
            """{"code":"TRANSIT","name":"Transit","kind":"TRANSIT","issueEligible":true,"areaId":"${area(token)}"}""").status).isEqualTo(400)
        assertThat(request("POST", "/api/v1/warehouse/skus", token, valid, " ").status).isEqualTo(400)
        create("skus", token, valid)
        assertThat(request("POST", "/api/v1/warehouse/skus", token, valid).status).isEqualTo(409)
    }
}
