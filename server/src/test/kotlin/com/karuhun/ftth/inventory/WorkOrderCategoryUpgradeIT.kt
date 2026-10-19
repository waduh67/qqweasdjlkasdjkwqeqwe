package com.karuhun.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection
import java.util.UUID

class WorkOrderCategoryUpgradeIT {
    private val mapper = jacksonObjectMapper()
    private val tables = listOf("work_order", "work_order_assignee", "work_order_reference_type", "work_order_reference", "work_order_reference_command")

    @Test fun `migration derives each historical category without rewriting command identity and restores tenant guards`() {
        WarehouseSchemaDatabase("216").use { database ->
            val tenants = List(2) { UUID.randomUUID() }
            database.ownerFixture { connection ->
                connection.autoCommit = false
                val sql = WarehouseSchemaFixture(connection)
                tables.forEach { sql.sql("ALTER TABLE $it DISABLE TRIGGER USER") }
                tenants.forEach { seed(connection, it) }
                tables.forEach { sql.sql("ALTER TABLE $it ENABLE TRIGGER USER") }
                connection.commit()
            }
            val before = read(database, tenants.first())
            assertThat(database.migrate("217").migrations.map { it.version }).containsExactly("217")
            tenants.forEach { tenant ->
                database.dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    val sql = WarehouseSchemaFixture(connection)
                    sql.sql("SET LOCAL app.tenant_id='$tenant'")
                    assertThat(sql.scalar("SELECT count(*) FROM work_order_reference_command")).isEqualTo("4")
                    assertThat(sql.scalar("SELECT snapshot->>'kpiCategory' FROM work_order_reference_type WHERE tenant_id='$tenant'")).isEqualTo("NONE")
                    assertThat(sql.scalar("SELECT snapshot->>'kpiCategory' FROM work_order_reference_command WHERE action='SEED'")).isEqualTo("PSB")
                    assertThat(sql.scalar("SELECT count(*) FROM work_order_reference WHERE snapshot->'type'->>'kpiCategory'='PSB'")).isEqualTo("1")
                    assertThat(sql.scalar("SELECT count(*) FROM work_order_reference WHERE snapshot->'type'->>'kpiCategory'='PROJECT'")).isEqualTo("1")
                    assertThat(sql.scalar("SELECT count(*) FROM work_order_reference_command WHERE action='CREATE' AND snapshot->'type'->>'kpiCategory' IN ('PSB','PROJECT')")).isEqualTo("2")
                    assertThat(sql.scalar("SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE relname IN ('work_order_reference_type','work_order_reference','work_order_reference_command')")).isEqualTo("t")
                    sql.reject("23514", "UPDATE work_order_reference_command SET notes='changed'")
                    sql.sql("SET LOCAL app.tenant_id=''")
                    assertThat(sql.scalar("SELECT count(*) FROM work_order_reference_command")).isEqualTo("0")
                    connection.rollback()
                }
            }
            assertThat(read(database, tenants.first())).isEqualTo(before)
            assertThat(database.migrate("217").migrationsExecuted).isZero()
        }
    }

    private fun seed(connection: Connection, tenant: UUID) {
        val sql = WarehouseSchemaFixture(connection)
        val actor = UUID.randomUUID(); val area = UUID.randomUUID(); val type = UUID.randomUUID()
        sql.sql("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','kpi-$tenant','KPI upgrade')")
        sql.sql("SET LOCAL app.tenant_id='$tenant'")
        sql.sql("INSERT INTO app_user(id,tenant_id,email,name,password_hash) VALUES ('$actor','$tenant','tech@example.test','Teknisi','fixture')")
        sql.sql("INSERT INTO area(id,tenant_id,code,name) VALUES ('$area','$tenant','KPI','Area KPI')")
        val historicType = mapOf("id" to type, "revision" to 0, "name" to "Pasang Baru", "workType" to "PSB", "materialRequired" to true,
            "photoSlots" to listOf("Bukti Pasang", "Bukti Kedatangan"), "active" to true, "deleted" to false)
        val latestType = historicType + mapOf("revision" to 1, "workType" to "REPAIR")
        insert(connection, "INSERT INTO work_order_reference_type(id,tenant_id,name,revision,snapshot) VALUES (?,?,?,1,?::jsonb)", type, tenant, "Pasang Baru", mapper.writeValueAsString(latestType))
        command(connection, tenant, type, "TYPE", "SEED", 0, null, historicType)
        command(connection, tenant, type, "TYPE", "TYPE_SAVE", 1, actor, latestType)
        listOf(false, true).forEachIndexed { index, hasCategory ->
            val id = UUID.randomUUID(); val code = "KPI-$index"
            val frozenType = if (hasCategory) historicType + mapOf("kpiCategory" to "PROJECT") else historicType
            val snapshot = mapOf("id" to id, "code" to code, "revision" to 0, "type" to frozenType, "title" to "Pekerjaan lama",
                "description" to "", "priority" to "NORMAL", "customerId" to null, "technicianId" to actor, "technicianName" to "Teknisi",
                "areaId" to area, "scheduledAt" to null, "state" to "PENDING", "assignmentGeneration" to 0,
                "lastActivityAt" to "2026-10-01T00:00:00Z", "blockedReason" to null, "createdAt" to "2026-10-01T00:00:00Z")
            insert(connection, "INSERT INTO work_order(id,tenant_id,code,type,title,description,status,created_by,area_id) VALUES (?,?,?,'PSB','Pekerjaan lama','','ASSIGNED',?,?)", id, tenant, code, actor, area)
            insert(connection, "INSERT INTO work_order_assignee(id,tenant_id,work_order_id,technician_id) VALUES (?,?,?,?)", UUID.randomUUID(), tenant, id, actor)
            insert(connection, "INSERT INTO work_order_reference(id,tenant_id,type_id,technician_id,area_id,revision,snapshot) VALUES (?,?,?,?,?,0,?::jsonb)", id, tenant, type, actor, area, mapper.writeValueAsString(snapshot))
            command(connection, tenant, id, "WO", "CREATE", 0, actor, snapshot)
        }
    }

    private fun command(connection: Connection, tenant: UUID, id: UUID, kind: String, action: String, revision: Int, actor: UUID?, snapshot: Map<String, Any?>) {
        insert(connection, """INSERT INTO work_order_reference_command(id,tenant_id,resource_id,resource_kind,action,operation_key,revision,actor_id,authority_epoch,cutover_epoch,canonical_payload,payload_hash,snapshot,notes,permission_code,original_status)
            VALUES (?,?,?,?,?,?,?,?,0,0,'{"input":{}}',encode(sha256(convert_to('{"input":{}}','UTF8')),'hex'),?::jsonb,'Historical fixture',?,201)""",
            UUID.randomUUID(), tenant, id, kind, action, UUID.randomUUID().toString(), revision, actor, mapper.writeValueAsString(snapshot), if (actor == null) "SYSTEM" else "OWNER")
    }

    private fun read(database: WarehouseSchemaDatabase, tenant: UUID): List<String> = database.dataSource.connection.use { connection ->
        connection.autoCommit = false
        WarehouseSchemaFixture(connection).sql("SET LOCAL app.tenant_id='$tenant'")
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id,canonical_payload,payload_hash,actor_id,operation_key,revision,created_at,created_xid FROM work_order_reference_command ORDER BY id").use { rows ->
                buildList { while (rows.next()) add((1..8).joinToString("|") { rows.getString(it) ?: "" }) }
            }
        }.also { connection.rollback() }
    }

    private fun insert(connection: Connection, query: String, vararg values: Any?) {
        connection.prepareStatement(query).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
}
