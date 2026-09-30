package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.security.FtthAuthenticationToken
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseScopePersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class WarehouseReceiptITProvenance : WarehouseReceiptHttpFixture() {
    @Test fun `legacy reserved conflicted and retired claims reject all receipt effects including earlier valid lines`() {
        val setup = setupReceipt()
        val database = fixture(setup.token)
        val owner = context.getBean(org.flywaydb.core.Flyway::class.java).configuration.dataSource
        for (state in listOf("LEGACY_RESERVED", "CONFLICT", "RETIRED")) {
            owner.connection.use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { statement ->
                    statement.execute("SET LOCAL app.tenant_id='${database.tenant}'")
                    statement.execute("INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state) VALUES ('${UUID.randomUUID()}','${database.tenant}','SERIAL','$state','$state')")
                }
                connection.commit()
            }
            val draft = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"2","serials":[{"serial":"valid-$state"},{"serial":" ${state.lowercase()} "}]}""")
            assertThat(request("POST", "/api/v1/warehouse/receipts/${draft.path("id").asString()}/receive", setup.token, """{"expectedRevision":0}""").status).isEqualTo(409)
        }
        database.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_identity_claim")).isEqualTo("3")
            assertThat(scalar("SELECT count(*) FROM inventory_segment")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection")).isEqualTo("0")
        }
    }

    @Test fun `costless reader and removed destination scope cannot expose receipt detail history or replay`() {
        val setup = setupReceipt()
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.cable}", setup.token,
            """{"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":false,"expectedRevision":0}""").status).isEqualTo(200)
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"PRIVATE","cost":{"totalMinor":"999","currency":"IDR"}}""")
        val id = draft.path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""", "receive-private")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
        val placement = """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[{"lineId":"${line.path("id").asString()}",
            "stockIdentityId":"${line.path("pieces")[0].path("stockIdentityId").asString()}","quantityBase":"1000","baseUnit":"MM"}]}"""
        transition(setup, id, "putaway", placement, "putaway-private")
        val (viewer, viewerId) = user(setup.token, setOf("inventory.receipt.view"))
        val viewerInfo = mapper.readTree(request("GET", "/api/me", viewer).contentAsString)
        assertThat(request("PUT", "/api/users/$viewerId/access", setup.token,
            mapper.writeValueAsString(mapOf("roleIds" to viewerInfo.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(setup.token))))).status).isEqualTo(200)
        replaceScope(setup, viewerId, setOf(setup.source, setup.inspection, setup.bin))
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", viewer).contentAsString).path("lines")[0].path("cost").isNull).isTrue()
        replaceScope(setup, viewerId, setOf(setup.source, setup.inspection))
        assertThat(request("GET", "/api/v1/warehouse/receipts/$id", viewer).status).isEqualTo(404)
        assertThat(request("GET", "/api/v1/warehouse/receipts/$id/history", viewer).status).isEqualTo(404)
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/receipts", viewer).contentAsString).path("totalElements").asLong()).isZero()
        val admin = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString).path("id").asString()
        replaceScope(setup, admin, setOf(setup.source, setup.inspection))
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/receive", setup.token, """{"expectedRevision":0}""", "receive-private").status).isEqualTo(404)
    }

    private fun replaceScope(setup: Setup, userId: String, locations: Set<String>) {
        val me = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString)
        val database = fixture(setup.token)
        SecurityContextHolder.getContext().authentication = FtthAuthenticationToken(AuthenticatedUser(UUID.fromString(me.path("id").asString()),
            database.tenant, "scope@example.test", "Scope", false, emptySet(), emptySet()))
        try {
            database.transaction { context.getBean(WarehouseScopePersistence::class.java).replace(UUID.fromString(userId), locations.map(UUID::fromString).toSet(), 0) }
        } finally { SecurityContextHolder.clearContext() }
    }
}
