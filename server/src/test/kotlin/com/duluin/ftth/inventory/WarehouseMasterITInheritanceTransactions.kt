package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

class WarehouseMasterITInheritanceTransactions : WarehouseInheritanceFixture() {
    @Test fun `coherent subtree site replacement validates final state not intermediate rows`() {
        val token=tenant(); val first=site(token,"A"); val second=site(token,"B")
        val root=location(token,"ROOT",null,first); val bridge=location(token,"BRIDGE",root,null); val leaf=location(token,"LEAF",bridge,first)
        fixture(token).transaction {
            sql("UPDATE inventory_location SET site_id='$second',revision=revision+1 WHERE id='$root'")
            sql("UPDATE inventory_location SET site_id='$second',revision=revision+1 WHERE id='$leaf'")
        }
        assertThat(request("GET","/api/v1/warehouse/locations/$leaf",token).status).isEqualTo(200)
    }

    @Test fun `depth31 valid depth32 overflow and subtree depth shift reject without bypass`() {
        val token=tenant(); val root=location(token,"ROOT",null,null)
        val fixture=fixture(token); var parent=root
        fixture.transaction {
            repeat(31) { val id=UUID.randomUUID(); insertLocation(id,parent,null,area(token)); parent=id.toString() }
        }
        assertThat(request("GET","/api/v1/warehouse/locations/$parent",token).status).isEqualTo(200)
        assertThat(request("POST","/api/v1/warehouse/locations",token,locationBody(token,"OVERFLOW",parent,null)).status).isEqualTo(400)
        val overflow=assertThrows<RuntimeException> { fixture.transaction { insertLocation(UUID.randomUUID(),parent,null,area(token)) } }
        assertThat(sqlState(overflow)).isEqualTo("23514")
        val destination=location(token,"DESTINATION",null,null)
        val shifted=assertThrows<RuntimeException> { fixture.transaction { sql("UPDATE inventory_location SET parent_location_id='$destination',revision=revision+1 WHERE id='$root'") } }
        assertThat(sqlState(shifted)).isEqualTo("23514")
    }

    @ParameterizedTest @ValueSource(strings=["REPEATABLE READ","SERIALIZABLE"])
    fun `stale snapshot cannot bypass topology revision fence`(isolation: String) {
        val token=tenant(); val root=location(token,"ROOT",null,null); val other=location(token,"OTHER",null,null)
        val fixture=fixture(token)
        open(fixture.tenant,isolation).use { stale ->
            stale.createStatement().use { statement -> statement.executeQuery("SELECT revision FROM inventory_location_topology_fence").use { rows -> check(rows.next()) } }
            fixture.transaction { sql("UPDATE inventory_location SET name='Fresh',revision=revision+1 WHERE id='$root'") }
            val failure=assertThrows<SQLException> {
                stale.createStatement().use { it.execute("UPDATE inventory_location SET name='Stale',revision=revision+1 WHERE id='$other'") }
                stale.commit()
            }
            assertThat(failure.sqlState).isEqualTo("40001")
            stale.rollback()
        }
    }

    private fun open(tenant: UUID, isolation: String): Connection = DriverManager.getConnection(
        System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD")).apply {
        autoCommit=false
        createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL $isolation"); it.execute("SET LOCAL app.tenant_id='$tenant'") }
    }
}
