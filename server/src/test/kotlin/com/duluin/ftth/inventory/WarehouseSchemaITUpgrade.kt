package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

class WarehouseSchemaITUpgrade {
    @Test
    fun `legacy canonical reservations match ROOT expansion and Kotlin whitespace trimming`() = isolated { schema, url, owner ->
        migrations(schema,url,"172").migrate()
        val tenant = UUID.randomUUID()
        val location = UUID.randomUUID()
        owner.createStatement().use {
            it.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','unicode-$tenant','Unicode')")
            it.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$location','$tenant','legacy','WAREHOUSE')")
        }
        for (raw in listOf("\tstraße\n", " STRASSE ", "\u2003ﬃ\u2003", "FFI")) {
            owner.prepareStatement("INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,status,location_id,custody_owner_id,custody_owner_kind) VALUES (?, ?, ?, ?, 'AVAILABLE', ?, ?, 'WAREHOUSE')").use {
                it.setObject(1, UUID.randomUUID()); it.setObject(2, tenant); it.setObject(3, UUID.randomUUID())
                it.setString(4, raw); it.setObject(5, location); it.setObject(6, location); it.executeUpdate()
            }
        }
        migrations(schema,url,"174.4").migrate()
        DriverManager.getConnection(url, env("SPRING_DATASOURCE_USERNAME"), env("SPRING_DATASOURCE_PASSWORD")).use { app ->
            app.createStatement().use { statement ->
                statement.execute("SET app.tenant_id='$tenant'")
                statement.executeQuery("SELECT raw_value,canonical_value FROM inventory_identity_candidate").use { rows ->
                    var count = 0
                    while (rows.next()) {
                        assertThat(rows.getString(2)).isEqualTo(rows.getString(1).trim().uppercase(java.util.Locale.ROOT))
                        count++
                    }
                    assertThat(count).isEqualTo(4)
                }
                statement.executeQuery("SELECT count(*) FROM inventory_identity_claim WHERE canonical_value IN ('STRASSE','FFI') AND state='CONFLICT' AND admitted_asset_id IS NULL").use {
                    it.next(); assertThat(it.getInt(1)).isEqualTo(2)
                }
            }
        }
    }

    @Test
    fun `all packaged migrations boot in a clean schema and validate twice`() = isolated { schema, url, owner ->
        val flyway = migrations(schema, url, "174.4")
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(175)
        flyway.validate()
        assertThat(flyway.migrate().migrationsExecuted).isZero()
        owner.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success AND installed_by='warehouse_owner' AND version IN ('173','174')").use {
                it.next()
                assertThat(it.getInt(1)).isEqualTo(2)
            }
        }
    }

    @Test
    fun `V172 collisions and unknown quantities survive expansion without a winner`() = isolated { schema, url, owner ->
        val baseline = migrations(schema, url, "172")
        assertThat(baseline.migrate().migrationsExecuted).isEqualTo(169)
        val tenant = UUID.randomUUID()
        val location = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val legacySku = UUID.randomUUID()
        owner.createStatement().use { statement ->
            statement.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','upgrade-$tenant','Upgrade')")
            statement.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$location','$tenant','legacy','WAREHOUSE')")
            for ((id, raw) in listOf(first to " Serial-A ", second to "serial-a")) {
                val mac = if (id == first) "aa-bb-cc-dd-ee-ff" else "AA:BB:CC:DD:EE:FF"
                statement.execute("""
                    INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,mac_address,status,location_id,custody_owner_id,custody_owner_kind)
                    VALUES ('$id','$tenant','$legacySku','$raw','$mac','AVAILABLE','$location','$location','WAREHOUSE')
                """.trimIndent())
            }
            val movement = UUID.randomUUID()
            statement.execute("INSERT INTO inventory_movement(id,tenant_id,operation_namespace,operation_key,payload_hash,actor_id,reason,server_received_at,kind,state) VALUES ('$movement','$tenant','legacy','key','old-hash','$first','legacy',now(),'RECEIPT','APPLIED')")
            statement.execute("INSERT INTO inventory_movement_leg(id,tenant_id,movement_id,direction,item_id,sku_id,location_id,quantity,serialized,custody_owner_id,custody_owner_kind,status) VALUES ('${UUID.randomUUID()}','$tenant','$movement','IN','$first','$legacySku','$location',1,true,'$location','WAREHOUSE','AVAILABLE')")
            statement.execute("INSERT INTO inventory_movement_leg(id,tenant_id,movement_id,direction,item_id,sku_id,location_id,quantity,serialized,custody_owner_id,custody_owner_kind,status) VALUES ('${UUID.randomUUID()}','$tenant','$movement','IN','${UUID.randomUUID()}','$legacySku','$location',82500,false,'$location','WAREHOUSE','AVAILABLE')")
            statement.execute("""
                INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,status,quantity,rebuilt_at)
                VALUES ('${UUID.randomUUID()}','$tenant','${UUID.randomUUID()}','$legacySku','$location','$location','WAREHOUSE','AVAILABLE',82500,now())
            """.trimIndent())
        }
        val expanded = migrations(schema, url, "174.4")
        assertThat(expanded.migrate().migrationsExecuted).isEqualTo(6)
        expanded.validate()
        DriverManager.getConnection(url, env("SPRING_DATASOURCE_USERNAME"), env("SPRING_DATASOURCE_PASSWORD")).use { app ->
            app.autoCommit = false
            app.createStatement().use { statement ->
                statement.execute("SET LOCAL app.tenant_id='$tenant'")
                statement.executeQuery("SELECT serial_number,warehouse_admission,base_unit,base_unit_candidate FROM inventory_serialized_asset ORDER BY serial_number").use {
                    assertThat(it.next()).isTrue()
                    assertThat(it.getString(1)).isEqualTo(" Serial-A ")
                    assertThat(it.getString(2)).isEqualTo("LEGACY_UNRESOLVED")
                    assertThat(it.getString(3)).isNull()
                    assertThat(it.getString(4)).isEqualTo("EA")
                    assertThat(it.next()).isTrue()
                    assertThat(it.getString(1)).isEqualTo("serial-a")
                }
                statement.executeQuery("SELECT state,admitted_asset_id FROM inventory_identity_claim WHERE canonical_value='SERIAL-A'").use {
                    assertThat(it.next()).isTrue()
                    assertThat(it.getString(1)).isEqualTo("CONFLICT")
                    assertThat(it.getObject(2)).isNull()
                    assertThat(it.next()).isFalse()
                }
                statement.executeQuery("SELECT count(*) FROM inventory_identity_candidate WHERE canonical_value='SERIAL-A'").use {
                    it.next(); assertThat(it.getInt(1)).isEqualTo(2)
                }
                statement.executeQuery("SELECT state FROM inventory_identity_claim WHERE identity_type='MAC' AND canonical_value='AABBCCDDEEFF'").use {
                    it.next(); assertThat(it.getString(1)).isEqualTo("CONFLICT")
                }
                statement.executeQuery("SELECT quantity,quantity_base,base_unit FROM inventory_movement_leg WHERE serialized").use {
                    it.next(); assertThat(it.getInt(1)).isEqualTo(1)
                    assertThat(it.getLong(2)).isEqualTo(1); assertThat(it.getString(3)).isEqualTo("EA")
                }
                statement.executeQuery("SELECT quantity,quantity_base,base_unit FROM inventory_movement_leg WHERE NOT serialized").use {
                    it.next(); assertThat(it.getInt(1)).isEqualTo(82500)
                    assertThat(it.getObject(2)).isNull(); assertThat(it.getString(3)).isNull()
                }
                statement.executeQuery("SELECT quantity,quantity_base,base_unit FROM inventory_balance_projection").use {
                    it.next(); assertThat(it.getInt(1)).isEqualTo(82500)
                    assertThat(it.getObject(2)).isNull(); assertThat(it.getString(3)).isNull()
                }
                assertThatThrownBy {
                    statement.execute("INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state,admitted_asset_id) VALUES ('${UUID.randomUUID()}','$tenant','SERIAL','SERIAL-A','ADMITTED','$first')")
                }.isInstanceOf(SQLException::class.java).hasMessageContaining("inventory_identity_claim_key_uq")
            }
            app.rollback()
        }
        assertThat(expanded.migrate().migrationsExecuted).isZero()
    }

    private fun migrations(schema: String, url: String, target: String): Flyway = Flyway.configure()
        .dataSource(url, env("SPRING_FLYWAY_USER"), env("SPRING_FLYWAY_PASSWORD"))
        .schemas(schema).defaultSchema(schema).target(target).locations("classpath:db/migration").load()

    private fun isolated(block: (String, String, Connection) -> Unit) {
        assertThat(env("WAREHOUSE_QA")).isEqualTo("true")
        val baseUrl = env("SPRING_DATASOURCE_URL")
        assertThat(baseUrl).isEqualTo("jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        val schema = "warehouse_schema_" + UUID.randomUUID().toString().replace("-", "")
        val url = "$baseUrl?currentSchema=$schema,public"
        DriverManager.getConnection(baseUrl, env("SPRING_FLYWAY_USER"), env("SPRING_FLYWAY_PASSWORD")).use { owner ->
            owner.createStatement().use {
                it.execute("CREATE SCHEMA $schema AUTHORIZATION warehouse_owner")
                it.execute("GRANT USAGE ON SCHEMA $schema TO warehouse_app")
                it.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA $schema GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO warehouse_app")
                it.execute("SET search_path TO $schema,public")
            }
            try {
                block(schema, url, owner)
            } finally {
                owner.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun env(name: String): String = requireNotNull(System.getenv(name)) { "warehouse runner required: $name" }
}
