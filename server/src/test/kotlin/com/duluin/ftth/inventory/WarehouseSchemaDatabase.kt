package com.duluin.ftth.inventory

import org.flywaydb.core.Flyway
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager
import java.util.UUID

internal class WarehouseSchemaDatabase(target: String? = "latest") : AutoCloseable {
    val databaseName = "warehouse_fixture_" + UUID.randomUUID().toString().replace("-", "")
    val schema = "public"
    private val baseUrl = requireNotNull(System.getenv("SPRING_DATASOURCE_URL"))
    val url = baseUrl.substringBeforeLast('/') + "/" + databaseName
    private val ownerUser = requireNotNull(System.getenv("SPRING_FLYWAY_USER"))
    private val ownerPassword = requireNotNull(System.getenv("SPRING_FLYWAY_PASSWORD"))
    val dataSource = DriverManagerDataSource().apply {
        setUrl(this@WarehouseSchemaDatabase.url)
        username = requireNotNull(System.getenv("SPRING_DATASOURCE_USERNAME"))
        password = requireNotNull(System.getenv("SPRING_DATASOURCE_PASSWORD"))
    }

    init {
        require(System.getenv("WAREHOUSE_QA") == "true" && baseUrl == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        fixtureDatabase("create")
        try {
            if (target != null) migrate(target)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun migrate(target: String = "latest"): org.flywaydb.core.api.output.MigrateResult {
        val before = sharedFunctions()
        return Flyway.configure().dataSource(url, ownerUser, ownerPassword)
            .schemas(schema).defaultSchema(schema).target(target).locations("classpath:db/migration").load().migrate().also {
                check(sharedFunctions() == before) { "A fixture migration changed the shared QA database" }
            }
    }

    fun ownerFixture(block: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection(url, ownerUser, ownerPassword).use(block)
    }

    private fun sharedFunctions(): String? = DriverManager.getConnection(baseUrl, ownerUser, ownerPassword).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("""SELECT md5(string_agg(pg_get_functiondef(p.oid), E'\\n' ORDER BY p.proname,p.oid))
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE n.nspname='public' AND p.proname LIKE 'warehouse_%' AND p.prokind IN ('f','p')""").use {
                check(it.next()); it.getString(1)
            }
        }
    }

    private fun fixtureDatabase(action: String) {
        val script = requireNotNull(System.getenv("WAREHOUSE_DATABASE_FIXTURE_SCRIPT"))
        val process = ProcessBuilder("bash", script, action, databaseName).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "Owned database fixture $action failed: $output" }
    }

    override fun close() = fixtureDatabase("drop")
}
