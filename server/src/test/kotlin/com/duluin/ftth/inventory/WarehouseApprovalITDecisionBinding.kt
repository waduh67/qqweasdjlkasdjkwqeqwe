package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.duluin.ftth.inventory.domain.model.InventoryApprovalDecision

class WarehouseApprovalITDecisionBinding : WarehouseApprovalHttpFixture() {
    @Test fun `durable SQL decisions reject wrong policy tier excluded actor and missing evidence snapshot`() {
        val case = pending()
        val foreign = approver(case.setup.token, listOf(case.setup.inspection, case.setup.source))
        configure(case.setup.token, policyBody(listOf(case.setup.inspection), listOf(case.checker.second), revision = 1))
        fixture(case.setup.token).transaction {
            val policy = UUID.fromString(scalar("SELECT policy_version_id FROM inventory_approval WHERE id='${case.id}'"))
            val otherPolicy = UUID.fromString(scalar("SELECT id FROM inventory_approval_policy_version ORDER BY revision DESC LIMIT 1"))
            val requester = UUID.fromString(scalar("SELECT requester_id FROM inventory_approval WHERE id='${case.id}'"))
            jdbc { connection ->
                for (mode in listOf("NULL_POLICY", "WRONG_POLICY", "WRONG_TIER", "REQUESTER", "UNSEALED", "NO_SNAPSHOT")) {
                    val point = connection.setSavepoint()
                    try {
                        assertThatThrownBy { insert(connection, tenant, UUID.fromString(case.id),
                            if (mode == "NULL_POLICY") null else if (mode == "WRONG_POLICY") otherPolicy else policy,
                            if (mode == "REQUESTER") requester else if (mode == "UNSEALED") UUID.fromString(foreign.second) else UUID.fromString(case.checker.second),
                            if (mode == "WRONG_TIER") 999 else 1, snapshot = mode != "NO_SNAPSHOT")
                            connection.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                        }.describedAs(mode).isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo("23514") }
                    } finally { connection.rollback(point) }
                }
            }
        }
        assertThat(decide(case).status).isEqualTo(200)
        counts(case, 1, 1)
    }
    @Test fun `direct SQL cannot reuse actor across independent required tiers`() {
        val setup = setupReceipt()
        val first = approver(setup.token, listOf(setup.source, setup.inspection))
        val second = approver(setup.token, listOf(setup.source, setup.inspection))
        val tier = """{"userIds":["${first.second}","${second.second}"],"roleIds":[],"minimumMinor":"%s"}"""
        configure(setup.token, """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${setup.inspection}"],
            "rules":[{"operation":"RECEIPT","tiers":[${tier.format("1")},${tier.format("100")}]}]}""")
        val case = submit(setup, first, draft(setup, costLine(setup)).path("id").asString())
        fixture(setup.token).transaction {
            val policy = UUID.fromString(scalar("SELECT policy_version_id FROM inventory_approval"))
            jdbc { connection ->
                val point = connection.setSavepoint()
                try {
                    insert(connection, tenant, UUID.fromString(case.id), policy, UUID.fromString(first.second), 1)
                    connection.createStatement().use { it.execute("UPDATE inventory_approval SET revision=1 WHERE id='${case.id}'") }
                    assertThatThrownBy { insert(connection, tenant, UUID.fromString(case.id), policy, UUID.fromString(first.second), 2, revision = 2) }
                        .isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo("23514") }
                } finally { connection.rollback(point) }
            }
        }
        counts(case, 0, 0)
    }
    @Test fun `deferred decision binding cannot disappear after tenant context is cleared`() {
        val case = pending()
        fixture(case.setup.token).transaction {
            val policy = UUID.fromString(scalar("SELECT policy_version_id FROM inventory_approval"))
            jdbc { connection ->
                val point = connection.setSavepoint()
                try {
                    insert(connection, tenant, UUID.fromString(case.id), policy, UUID.fromString(case.checker.second), 1)
                    connection.createStatement().use { statement ->
                        statement.execute("SET CONSTRAINTS warehouse_approval_terminal IMMEDIATE")
                        statement.execute("SET LOCAL app.tenant_id=''")
                        assertThatThrownBy { statement.execute("SET CONSTRAINTS warehouse_approval_decision_bound IMMEDIATE") }
                            .isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo("23514") }
                    }
                } finally { connection.rollback(point) }
            }
        }
    }
    private fun insert(connection: Connection, tenant: UUID, approval: UUID, policy: UUID?, actor: UUID, tier: Int,
        revision: Long = 1, snapshot: Boolean = true) {
        val id = UUID.randomUUID()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val evidence = WarehouseApprovalDecisionRecord(id, tier, actor, InventoryApprovalDecision.APPROVE, null, now, revision, null, 0, null)
        connection.prepareStatement("""INSERT INTO inventory_approval_decision(id,tenant_id,approval_id,tier,approver_id,decision,decided_at,revision,
            operation_key,operation_hash,policy_version_id,authority_epoch,independence_snapshot) VALUES (?,?,?,?,?,'APPROVE',?,?,?,?,?,0,?::jsonb)""").use { statement ->
            val values = listOf(id, tenant, approval, tier, actor, Timestamp.from(now), revision, id.toString(), "a".repeat(64), policy,
                if (snapshot) mapper.writeValueAsString(evidence) else null)
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
    @Test fun `AV12-3 null policy requester decisions cannot attach to durable requests`() {
        val case = pending()
        fixture(case.setup.token).transaction {
            jdbc { connection ->
                val point = connection.setSavepoint()
                try {
                    assertThatThrownBy { connection.createStatement().use { statement ->
                        statement.execute("""INSERT INTO inventory_approval_decision(id,tenant_id,approval_id,tier,approver_id,decision,decided_at,revision,operation_key,operation_hash)
                            SELECT '${UUID.randomUUID()}',tenant_id,id,999,requester_id,'APPROVE',clock_timestamp(),1,'forged-null-policy','${"a".repeat(64)}'
                            FROM inventory_approval WHERE id='${case.id}'""")
                        statement.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    } }.isInstanceOfSatisfying(SQLException::class.java) { assertThat(it.sqlState).isEqualTo("23514") }
                } finally { connection.rollback(point) }
            }
        }
        counts(case, 0, 0)
    }
}
