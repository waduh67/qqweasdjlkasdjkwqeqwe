package com.duluin.ftth.common.infrastructure.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Loopback browser harness must prove which database and role answered, not merely HTTP 200. */
@Component("warehouseHealthIndicator")
@Profile("warehouse-e2e")
class WarehouseQaHealthIndicator(
    private val jdbc: JdbcTemplate,
    @Value("\${WAREHOUSE_ENVIRONMENT_MARKER:}") private val expectedMarker: String,
    @Value("\${WAREHOUSE_E2E_DATABASE:warehouse_e2e}") private val expectedDatabase: String,
) : HealthIndicator {
    override fun health(): Health {
        if (!expectedMarker.matches(Regex("warehouse-[a-f0-9]{12}-[a-f0-9]{32}"))) {
            return Health.down().withDetail("reason", "Missing owned environment marker").build()
        }
        if (expectedDatabase != "warehouse_e2e" && !expectedDatabase.matches(Regex("warehouse_fixture_[a-f0-9]{32}"))) {
            return Health.down().withDetail("reason", "Invalid owned browser database name").build()
        }
        return try {
            val rows = jdbc.queryForList("""
                SELECT current_database() AS database, current_user AS username, marker,
                       NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AS restricted
                FROM warehouse_environment.identity, pg_roles WHERE rolname = current_user
            """.trimIndent())
            val row = rows.singleOrNull()
            if (row == null || row["database"] != expectedDatabase || row["username"] != "warehouse_app" ||
                row["marker"] != expectedMarker || row["restricted"] != true) {
                Health.down().withDetail("reason", "Database, role or marker does not match the owned browser environment").build()
            } else {
                Health.up().withDetail("database", requireNotNull(row["database"]))
                    .withDetail("user", requireNotNull(row["username"])).withDetail("marker", expectedMarker).build()
            }
        } catch (_: Exception) {
            Health.down().withDetail("reason", "Owned database identity could not be read").build()
        }
    }
}
