package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseMigrationITBoot {
    @Test fun `all packaged migrations preserve colliding raw identities units and installed links before admission`() {
        WarehouseSchemaDatabase("172").use { database ->
            val old = WarehouseMigrationLegacyFixture()
            database.ownerFixture(old::seed)

            WarehouseMigrationInventory.assertUpgrade("172", database.migrate())

            database.dataSource.connection.use { connection ->
                connection.autoCommit = false
                val sql = WarehouseSchemaFixture(connection)
                sql.sql("SET LOCAL app.tenant_id='${old.tenant}'")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_serialized_asset WHERE warehouse_admission='LEGACY_UNRESOLVED'")).isEqualTo("3")
                assertThat(sql.scalar("SELECT serial_number FROM inventory_serialized_asset WHERE id='${old.first}'")).isEqualTo(" Serial-A ")
                assertThat(sql.scalar("SELECT installed_onu_id FROM inventory_serialized_asset WHERE id='${old.installed}'")).isEqualTo(old.episode.toString())
                assertThat(sql.scalar("SELECT customer_id FROM onu WHERE id='${old.episode}'")).isEqualTo(old.customer.toString())
                assertThat(sql.scalar("SELECT count(*) FROM onu WHERE warehouse_admission='LEGACY_UNRESOLVED' AND provenance='UNKNOWN' AND asset_id IS NULL")).isEqualTo("2")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_identity_claim WHERE state='CONFLICT' AND canonical_value IN ('SERIAL-A','AABBCCDDEEFF','INSTALLED-UNIQUE') AND admitted_asset_id IS NULL")).isEqualTo("3")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_identity_candidate WHERE source_id IN ('${old.first}','${old.second}')")).isEqualTo("4")
                assertThat(sql.scalar("SELECT quantity::text || ':' || coalesce(base_unit,'unknown') FROM inventory_balance_projection WHERE id='${old.balance}'")).isEqualTo("82500:unknown")
                assertThat(sql.scalar("SELECT quantity_base::text || ':' || base_unit FROM inventory_movement_leg WHERE id='${old.serializedLeg}'")).isEqualTo("1:EA")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_movement_leg WHERE id='${old.bulkLeg}' AND quantity=82500 AND quantity_base IS NULL AND base_unit IS NULL")).isEqualTo("1")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
                assertThat(sql.scalar("SELECT state FROM inventory_tenant_cutover")).isEqualTo("LEGACY")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_provenance_case")).isEqualTo("11")
                assertThat(sql.scalar("SELECT source_snapshot->>'legacyQuantity' FROM inventory_provenance_case WHERE source_id='${old.balance}'")).isEqualTo("82500")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_provenance_case WHERE source_id='${old.balance}' AND source_snapshot->'baseUnit'='null'::jsonb")).isEqualTo("1")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_provenance_case WHERE source_snapshot::text LIKE '%Private address%'")).isEqualTo("0")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_provenance_case WHERE source_hash=encode(sha256(convert_to(source_snapshot::text,'UTF8')),'hex')")).isEqualTo("11")
                sql.reject("42501", "UPDATE inventory_provenance_case SET source_snapshot='{}'")
                sql.reject("42501", "DELETE FROM inventory_provenance_case")
                sql.reject("42501", "INSERT INTO inventory_provenance_case(tenant_id,source_table,source_id,source_snapshot) VALUES ('${old.tenant}','onu','${old.episode}','{}')")
                sql.reject("23505", "INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state,admitted_asset_id) VALUES ('${java.util.UUID.randomUUID()}','${old.tenant}','SERIAL','SERIAL-A','ADMITTED','${old.first}')")
                val batch = java.util.UUID.randomUUID()
                val batchInsert = """INSERT INTO inventory_migration_batch(id,tenant_id,cutover_epoch,snapshot_watermark,requested_by,source_manifest,source_hash)
                    VALUES ('$batch','${old.tenant}',1,'2026-01-01T00:00:00Z','${old.customer}','[]','${"0".repeat(64)}')"""
                sql.reject("23514", batchInsert)
                sql.sql("""UPDATE inventory_tenant_cutover SET state='VALIDATING',epoch=1,revision=1,migration_batch_id='$batch',
                    snapshot_watermark='2026-01-01T00:00:00Z',pending_legacy_effect_ids=ARRAY['${old.pending}'::uuid]""")
                sql.sql(batchInsert)
                assertThat(sql.scalar("SELECT source_count FROM inventory_migration_batch")).isEqualTo("11")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_migration_batch WHERE source_hash=encode(sha256(convert_to(source_manifest::text,'UTF8')),'hex') AND source_hash<>'${"0".repeat(64)}'")).isEqualTo("1")
                sql.reject("42501", "UPDATE inventory_migration_batch SET source_manifest='[]'")
                sql.reject("42501", "DELETE FROM inventory_migration_batch")
                sql.reject("42501", "UPDATE inventory_tenant_cutover SET state='ENFORCED',epoch=2,revision=2")
                sql.sql("SET LOCAL app.tenant_id='${old.otherTenant}'")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_provenance_case")).isEqualTo("0")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_migration_batch")).isEqualTo("0")
                assertThat(sql.scalar("SELECT state FROM inventory_tenant_cutover")).isEqualTo("LEGACY")
                connection.rollback()
            }
            assertThat(database.migrate().migrationsExecuted).isZero()
        }
    }

    @Test fun `empty full packaged migration has zero preserved stock and validates on repeat`() {
        WarehouseSchemaDatabase().use { database ->
            database.ownerFixture { connection ->
                val sql = WarehouseSchemaFixture(connection)
                assertThat(sql.scalar("SELECT count(*) FROM inventory_provenance_case")).isEqualTo("0")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_migration_batch")).isEqualTo("0")
                assertThat(sql.scalar("SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
            }
            assertThat(database.migrate().migrationsExecuted).isZero()
        }
    }
}
