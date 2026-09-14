package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID
import java.time.Instant
import com.duluin.ftth.inventory.AssetLegalOwner
import com.duluin.ftth.inventory.AssetOwnershipMode
import com.duluin.ftth.inventory.AssetProvenance
import com.duluin.ftth.inventory.DeploymentPurpose
import com.duluin.ftth.inventory.application.port.outbound.AssetAssignmentStore
import com.duluin.ftth.inventory.application.port.outbound.NewAssetAssignment
import com.duluin.ftth.inventory.application.port.outbound.AssetAssignmentClosure

class CustomerAssetSchemaITIntegrity : CustomerAssetEpisodeFixture() {
    @Test
    fun `verified ONU cannot hide malformed raw identity behind a valid canonical claim`() {
        val fixture = episodeCase()
        val assignment = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.assignment(assignment)) }
        val malformed = fixture.episode(assignment).replace("'${fixture.serial}','${fixture.serial}'", "'   ','${fixture.serial}'")
        val failure = assertThrows<Exception> { fixture.stock.transaction { sql(malformed) } }
        assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23514")
    }

    @Test
    fun `typed assignment owner persistence is available without installing deployment commands`() {
        assertThat(context.containsBean("assetAssignmentPersistence")).isTrue()
        assertThat(context.getBeansOfType(com.duluin.ftth.inventory.InventoryDeploymentApi::class.java)).isEmpty()
    }

    @Test
    fun `owner adapter appends closes and reads immutable customer history`() {
        val fixture = episodeCase()
        val store = context.getBean(AssetAssignmentStore::class.java)
        val id = UUID.randomUUID()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-02-01T00:00:00Z")
        fixture.stock.transaction {
            store.append(NewAssetAssignment(id, fixture.asset, fixture.customerA, fixture.workOrder, fixture.issueLine,
                DeploymentPurpose.INSTALL, AssetOwnershipMode.LOAN, AssetLegalOwner.ISP, AssetProvenance.RECEIPT,
                fixture.actor, start))
        }
        fixture.stock.transaction { store.close(AssetAssignmentClosure(id, 0, end)) }
        fixture.stock.transaction {
            val stored = store.history(fixture.asset).single()
            assertThat(stored.customerId).isEqualTo(fixture.customerA)
            assertThat(stored.startedAt).isEqualTo(start)
            assertThat(stored.endedAt).isEqualTo(end)
            assertThat(stored.revision).isEqualTo(1)
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment_history WHERE assignment_id='$id'")).isEqualTo("2")
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["PROVENANCE", "TITLE"])
    fun `new assignment snapshots must describe the physical asset`(field: String) {
        val fixture = episodeCase()
        val query = when (field) {
            "PROVENANCE" -> fixture.assignment(UUID.randomUUID()).replace("'RECEIPT'", "'OPENING_BALANCE'")
            "TITLE" -> fixture.assignment(UUID.randomUUID()).replace("'ISP'", "'CUSTOMER'")
            else -> error("Unknown field")
        }
        val failure = assertThrows<Exception> { fixture.stock.transaction { sql(query) } }
        assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23514")
    }

    @Test
    fun `topology changes retain a timestamped installation history`() {
        val fixture = episodeCase()
        val assignment = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.assignment(assignment)); sql(fixture.episode(assignment)) }
        fixture.stock.transaction { sql("UPDATE onu SET installed_at='2026-02-01T00:00:00Z' WHERE assignment_id='$assignment'") }
        fixture.stock.transaction {
            assertThat(scalar("SELECT count(*) FROM onu_topology_history WHERE assignment_id='$assignment'")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM onu_topology_history WHERE assignment_id='$assignment' AND revision=0 AND snapshot->>'installedAt' IS NULL")).isEqualTo("1")
        }
    }
}
