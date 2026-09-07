package com.duluin.ftth.inventory

import org.flywaydb.core.Flyway
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager
import java.util.UUID

internal class WarehouseSchemaDatabase(target: String = "latest") : AutoCloseable {
    val schema = "warehouse_schema_" + UUID.randomUUID().toString().replace("-", "")
    private val baseUrl = requireNotNull(System.getenv("SPRING_DATASOURCE_URL"))
    val url = "$baseUrl?currentSchema=$schema,public"
    private val ownerUser = requireNotNull(System.getenv("SPRING_FLYWAY_USER"))
    private val ownerPassword = requireNotNull(System.getenv("SPRING_FLYWAY_PASSWORD"))
    val dataSource = DriverManagerDataSource().apply {
        setUrl(this@WarehouseSchemaDatabase.url)
        username = requireNotNull(System.getenv("SPRING_DATASOURCE_USERNAME"))
        password = requireNotNull(System.getenv("SPRING_DATASOURCE_PASSWORD"))
    }

    init {
        require(System.getenv("WAREHOUSE_QA") == "true" && baseUrl == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        owner { connection ->
            connection.createStatement().use {
                it.execute("CREATE SCHEMA $schema AUTHORIZATION warehouse_owner")
                it.execute("GRANT USAGE ON SCHEMA $schema TO warehouse_app")
                it.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA $schema GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO warehouse_app")
            }
        }
        try {
            migrate(target)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun migrate(target: String = "latest") = Flyway.configure().dataSource(url, ownerUser, ownerPassword)
        .schemas(schema).defaultSchema(schema).target(target).locations("classpath:db/migration").load().migrate()

    fun ownerFixture(block: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection(url, ownerUser, ownerPassword).use(block)
    }

    private fun owner(block: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection(baseUrl, ownerUser, ownerPassword).use(block)
    }

    override fun close() = owner { connection -> connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
}
