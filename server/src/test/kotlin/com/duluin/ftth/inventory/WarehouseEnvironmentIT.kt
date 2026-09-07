package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.storage.S3ObjectStorage
import com.duluin.ftth.common.infrastructure.storage.S3StorageConfig
import com.duluin.ftth.common.infrastructure.storage.StorageProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest
@ActiveProfiles("test")
class WarehouseEnvironmentIT {
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var flyway: Flyway
    @Autowired private lateinit var storageProperties: StorageProperties
    @TempDir lateinit var temporary: Path
    private val root = Path.of(System.getProperty("user.dir")).parent

    @Test
    fun `runner refuses forbidden datasource before any database access`() {
        val process = ProcessBuilder("bash", root.resolve("scripts/warehouse/qa.sh").toString(), "server")
            .directory(root.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().keys.removeIf { it.startsWith("SPRING_") || it.startsWith("FTTH_") }
                environment()["SPRING_DATASOURCE_URL"] = "jdbc:postgresql://127.0.0.1:1/ftth"
            }
            .start()
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue()
        val output = process.inputStream.bufferedReader().readText()
        assertThat(process.exitValue()).isEqualTo(64)
        assertThat(output).contains("REFUSED: external configuration override")
    }

    @Test
    fun `runner refuses JVM launcher overrides before Docker access`() {
        val launchers = listOf("_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS")
        val binaries = Files.createDirectories(temporary.resolve("bin"))
        val sentinel = temporary.resolve("docker-access")
        for (name in listOf("docker", "sudo")) {
            val executable = Files.writeString(
                binaries.resolve(name),
                "#!/bin/sh\nprintf touched > \"\$JVM_GUARD_SENTINEL\"\nexit 99\n",
            )
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        }
        for (launcher in launchers) {
            val process = ProcessBuilder(
                "bash", root.resolve("scripts/warehouse/qa.sh").toString(), "server",
                "--tests", "*WarehouseEnvironmentIT*", "--no-parallel",
            ).directory(root.toFile()).redirectErrorStream(true).apply {
                environment().keys.removeIf { it.startsWith("SPRING_") || it.startsWith("FTTH_") || it in launchers }
                environment()["PATH"] = "$binaries:${System.getenv("PATH")}"
                environment()["JVM_GUARD_SENTINEL"] = sentinel.toString()
                environment()[launcher] = "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:1/ftth -Dspring.flyway.url=jdbc:postgresql://127.0.0.1:1/ftth"
            }.start()
            try {
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue()
                assertThat(process.exitValue()).isEqualTo(64)
                assertThat(process.inputStream.bufferedReader().readText())
                    .contains("REFUSED: external configuration override ($launcher)")
                    .doesNotContain("PASS:", "> Task", "Picked up", "Flyway")
                assertThat(Files.exists(sentinel)).isFalse()
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        }
    }

    @Test
    fun `runner refuses missing marker and unknown command`() {
        val scripts = Files.createDirectories(temporary.resolve("scripts/warehouse"))
        for (name in listOf("qa.sh", "test-environment.sh")) {
            Files.copy(root.resolve("scripts/warehouse/$name"), scripts.resolve(name))
        }
        for ((command, message) in listOf("server" to "missing generated environment marker", "invalid" to "unknown QA mode")) {
            val process = ProcessBuilder("bash", scripts.resolve("qa.sh").toString(), command)
                .redirectErrorStream(true)
                .apply { environment().keys.removeIf { it.startsWith("SPRING_") || it.startsWith("FTTH_") } }
                .start()
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue()
            assertThat(process.exitValue()).isEqualTo(64)
            assertThat(process.inputStream.bufferedReader().readText()).contains(message)
        }
    }

    @Test
    fun `real isolated MinIO stores and retrieves an object`() {
        val bucket = System.getenv("FTTH_S3_BUCKET")
        assertThat(bucket).isEqualTo("warehouse-test")
        val key = "environment-proof/${UUID.randomUUID()}"
        val body = "warehouse isolation proof"
        assertThat(storageProperties.endpoint).isEqualTo(System.getenv("FTTH_S3_ENDPOINT"))
        S3StorageConfig().s3Client(storageProperties).use { client ->
            val storage = S3ObjectStorage(client, storageProperties)
            storage.ensureBucket()
            try {
                storage.put(key, "text/plain", body.toByteArray())
                assertThat(storage.get(key).bytes.decodeToString()).isEqualTo(body)
            } finally {
                storage.delete(key)
            }
        }
    }

    @Test
    fun `Spring datasource uses isolated nonowner role and Flyway migrates as separate owner`() {
        dataSource.connection.use { connection ->
            assertThat(connection.metaData.url).isEqualTo(System.getenv("SPRING_DATASOURCE_URL"))
            connection.createStatement().use { statement ->
                statement.executeQuery("""
                    SELECT current_database(), current_user, rolsuper, rolbypassrls,
                           pg_get_userbyid(datdba), pg_has_role(current_user, 'warehouse_owner', 'MEMBER'), marker
                    FROM pg_roles, pg_database, warehouse_environment.identity
                    WHERE rolname=current_user AND datname=current_database()
                """.trimIndent()).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString(1)).isEqualTo("warehouse_test")
                    assertThat(result.getString(2)).isEqualTo("warehouse_app")
                    assertThat(result.getBoolean(3)).isFalse()
                    assertThat(result.getBoolean(4)).isFalse()
                    assertThat(result.getString(5)).isEqualTo("warehouse_owner")
                    assertThat(result.getBoolean(6)).isFalse()
                    assertThat(result.getString(7)).isEqualTo(System.getenv("WAREHOUSE_ENVIRONMENT_MARKER"))
                }
                statement.executeQuery("SELECT count(*) FROM pg_extension WHERE extname IN ('postgis','timescaledb')").use { result ->
                    result.next()
                    assertThat(result.getInt(1)).isEqualTo(2)
                }
                statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success AND installed_by='warehouse_owner'").use { result ->
                    result.next()
                    assertThat(result.getInt(1)).isGreaterThanOrEqualTo(169)
                }
            }
        }
        flyway.validate()
        flyway.configuration.dataSource.connection.use { connection ->
            assertThat(connection.metaData.userName).isEqualTo("warehouse_owner")
        }
    }

    @Test
    fun `real RLS hides foreign inventory rows and rejects cross tenant insert`() {
        val tenant = UUID.randomUUID()
        val foreign = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("SET LOCAL app.tenant_id='$tenant'")
                    statement.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('${UUID.randomUUID()}','$tenant','warehouse-env-probe','WAREHOUSE')")
                    statement.executeQuery("SELECT count(*) FROM inventory_location WHERE code='warehouse-env-probe'").use { result ->
                        result.next()
                        assertThat(result.getInt(1)).isEqualTo(1)
                    }
                    statement.execute("SET LOCAL app.tenant_id='$foreign'")
                    statement.executeQuery("SELECT count(*) FROM inventory_location WHERE code='warehouse-env-probe'").use { result ->
                        result.next()
                        assertThat(result.getInt(1)).isZero()
                    }
                    assertThatThrownBy {
                        statement.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('${UUID.randomUUID()}','$tenant','denied','WAREHOUSE')")
                    }.isInstanceOf(SQLException::class.java).hasMessageContaining("row-level security")
                }
            } finally {
                connection.rollback()
            }
        }
    }
}
