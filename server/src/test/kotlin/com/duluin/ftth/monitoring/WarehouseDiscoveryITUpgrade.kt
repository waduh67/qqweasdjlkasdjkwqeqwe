package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerObservationApi
import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import com.duluin.ftth.inventory.postingContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.util.UUID

class WarehouseDiscoveryITUpgrade {
    @Test
    fun `forward upgrade boots with legacy ambiguity and leaves old removal validator unchanged`() {
        WarehouseSchemaDatabase("172").use { database ->
            val tenant = UUID.randomUUID()
            val customer = UUID.randomUUID()
            val foreignTenant = UUID.randomUUID()
            val foreignCustomer = UUID.randomUUID()
            val firstOnu = UUID.randomUUID()
            val foreignOnu = UUID.randomUUID()
            database.ownerFixture { connection -> connection.createStatement().use { query ->
                query.execute("SET app.tenant_id='$tenant'")
                query.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','discovery-upgrade-$tenant','Upgrade')")
                query.execute("INSERT INTO customer(id,tenant_id,code,name,address) VALUES ('$customer','$tenant','UPGRADE','Upgrade','Test')")
                for ((index, serial) in listOf(" Serial-X ", "serial-x").withIndex()) query.execute("""INSERT INTO onu(id,tenant_id,customer_id,serial_number)
                    VALUES ('${if (index == 0) firstOnu else UUID.randomUUID()}','$tenant','$customer','$serial')""")
                query.execute("INSERT INTO tenant(id,slug,name) VALUES ('$foreignTenant','discovery-foreign-$foreignTenant','Foreign upgrade')")
                query.execute("SET app.tenant_id='$foreignTenant'")
                query.execute("INSERT INTO customer(id,tenant_id,code,name,address) VALUES ('$foreignCustomer','$foreignTenant','FOREIGN','Foreign','Test')")
                query.execute("INSERT INTO onu(id,tenant_id,customer_id,serial_number) VALUES ('$foreignOnu','$foreignTenant','$foreignCustomer','SERIAL-X')")
                for ((scope, owner, onu) in listOf(Triple(tenant, customer, firstOnu), Triple(foreignTenant, foreignCustomer, foreignOnu))) {
                    query.execute("SET app.tenant_id='$scope'")
                    query.execute("""INSERT INTO cpe_device(id,tenant_id,genieacs_id,serial_number,customer_id,onu_id,last_inform_at,ssid)
                        VALUES ('${UUID.randomUUID()}','$scope','LEGACY-SHARED-ACS','SERIAL-X','$owner','$onu',clock_timestamp(),'retained-private')""")
                }
            } }
            database.migrate("175.89")
            val before = database.dataSource.connection.use { guard(it) }
            assertThat(before).contains("warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END)")
            database.migrate()
            database.dataSource.connection.use { assertThat(guard(it)).isEqualTo(before) }
            postingContext(database).use { context ->
                TenantContext.runAs(tenant) {
                    val result = context.getBean(CustomerObservationApi::class.java).resolveObservation("SERIAL-X", Instant.now())
                    assertThat(result.episode).isNull()
                    assertThat(result.reason).isEqualTo("AMBIGUOUS_EPISODE")
                }
                TenantContext.runAs(foreignTenant) {
                    assertThat(context.getBean(com.duluin.ftth.cpe.CpeApi::class.java).findDevicesForCustomer(foreignCustomer)).isEmpty()
                }
            }
            database.dataSource.connection.use { connection -> connection.createStatement().use { query ->
                query.execute("SET app.tenant_id='$tenant'")
                query.executeQuery("SELECT count(*) FROM onu WHERE customer_id='$customer'").use { rows -> rows.next(); assertThat(rows.getInt(1)).isEqualTo(2) }
                query.executeQuery("SELECT count(*) FROM inventory_serialized_asset").use { rows -> rows.next(); assertThat(rows.getInt(1)).isZero() }
                query.executeQuery("SELECT count(*) FROM cpe_observation_conflict").use { rows -> rows.next(); assertThat(rows.getInt(1)).isEqualTo(1) }
                query.executeQuery("SELECT ssid FROM cpe_device").use { rows -> rows.next(); assertThat(rows.getString(1)).isEqualTo("retained-private") }
            } }
        }
    }

    private fun guard(connection: Connection): String = connection.createStatement().use { query ->
        query.executeQuery("SELECT prosrc FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname='warehouse_asset_removal_final_guard'")
            .use { rows -> check(rows.next()); rows.getString(1) }
    }
}
