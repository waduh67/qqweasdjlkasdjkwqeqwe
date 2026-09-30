package com.duluin.ftth.inventory

import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

/** A real owner policy and real database time; no application clock override. */
internal class WarehouseDraftClockFixture(private val database: WarehousePostingFixture) {
    fun policy(seconds: Int) {
        check(System.getenv("WAREHOUSE_QA") == "true")
        val url = database.context.environment.getRequiredProperty("spring.datasource.url")
        check(url == "jdbc:postgresql://127.0.0.1:25432/warehouse_test" ||
            url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:25432/warehouse_fixture_[a-f0-9]{32}")))
        DriverManager.getConnection(url, System.getenv("SPRING_FLYWAY_USER"), System.getenv("SPRING_FLYWAY_PASSWORD")).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)").use {
                it.setString(1, database.tenant.toString()); it.execute()
            }
            connection.prepareStatement("""INSERT INTO inventory_draft_policy(tenant_id,version,ttl_seconds)
                SELECT ?,coalesce(max(version),0)+1,? FROM inventory_draft_policy WHERE tenant_id=?""").use {
                it.setObject(1, database.tenant); it.setInt(2, seconds); it.setObject(3, database.tenant); it.executeUpdate()
            }
            connection.commit()
        }
    }

    fun awaitDocument(id: String) = await("document", id)
    fun awaitPlan(id: String) = await("plan", id)
    fun activity(id: String, family: String = "document"): String {
        require(family in setOf("document", "plan"))
        return database.transaction { scalar("SELECT jsonb_agg(to_jsonb(a) ORDER BY source_revision)::text FROM inventory_${family}_draft_activity a WHERE ${family}_id='$id'") }
    }
    private fun await(family: String, id: String) {
        val limit = Instant.now().plus(Duration.ofSeconds(15))
        while (!database.transaction {
            scalar("SELECT (deadline<=clock_timestamp())::text FROM inventory_${family}_draft_activity WHERE ${family}_id='$id' ORDER BY source_revision DESC LIMIT 1").toBoolean()
        }) {
            check(Instant.now().isBefore(limit)) { "Database draft deadline was not reached" }
            Thread.sleep(25)
        }
    }
}
