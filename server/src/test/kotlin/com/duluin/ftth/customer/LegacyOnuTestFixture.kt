package com.duluin.ftth.customer

import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

object LegacyOnuTestFixture {
    fun stage(customerId: String, serial: String, createdAt: Instant = Instant.now()): String {
        check(System.getenv("WAREHOUSE_QA") == "true")
        val url = requireNotNull(System.getenv("SPRING_DATASOURCE_URL"))
        check(url == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        val id = UUID.randomUUID()
        DriverManager.getConnection(url, requireNotNull(System.getenv("SPRING_FLYWAY_USER")),
            requireNotNull(System.getenv("SPRING_FLYWAY_PASSWORD"))).use { connection ->
            connection.autoCommit = false
            val tenant = connection.prepareStatement("SELECT tenant_id FROM customer WHERE id=?").use { statement ->
                statement.setObject(1, UUID.fromString(customerId))
                statement.executeQuery().use { rows -> check(rows.next()); rows.getObject(1, UUID::class.java) }
            }
            connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)").use {
                it.setString(1, tenant.toString()); it.execute()
            }
            connection.prepareStatement("""INSERT INTO onu(id,tenant_id,customer_id,serial_number,canonical_serial_candidate,warehouse_admission,created_at)
                VALUES (?,?,?,?,warehouse_canonical_serial(?),'LEGACY_UNRESOLVED',?)""").use {
                it.setObject(1, id); it.setObject(2, tenant); it.setObject(3, UUID.fromString(customerId))
                it.setString(4, serial); it.setString(5, serial); it.setTimestamp(6, Timestamp.from(createdAt)); it.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state)
                VALUES (?,?,'SERIAL',warehouse_canonical_serial(?),'LEGACY_RESERVED')""").use {
                it.setObject(1, UUID.randomUUID()); it.setObject(2, tenant); it.setString(3, serial); it.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO inventory_identity_candidate(id,tenant_id,claim_id,identity_type,source_table,source_id,raw_value,canonical_value)
                SELECT ?,tenant_id,id,'SERIAL','onu',?,?,canonical_value FROM inventory_identity_claim
                WHERE tenant_id=? AND identity_type='SERIAL' AND canonical_value=warehouse_canonical_serial(?)""").use {
                it.setObject(1, UUID.randomUUID()); it.setObject(2, id); it.setString(3, serial)
                it.setObject(4, tenant); it.setString(5, serial); it.executeUpdate()
            }
            connection.commit()
        }
        return id.toString()
    }
}
