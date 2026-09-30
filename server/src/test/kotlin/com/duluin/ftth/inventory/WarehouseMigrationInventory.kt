package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.output.MigrateResult
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.security.MessageDigest
import java.util.HexFormat

internal object WarehouseMigrationInventory {
    private val filename = Regex("V([0-9]+(?:_[0-9]+)*)__.+\\.sql")
    val required: List<String> = requireNotNull(javaClass.getResourceAsStream("/warehouse/task17-migration-inventory.txt"))
        .bufferedReader().use { it.readLines().filter(String::isNotBlank) }

    private fun version(name: String): MigrationVersion = MigrationVersion.fromVersion(
        requireNotNull(filename.matchEntire(name)) { "Invalid versioned migration filename: $name" }.groupValues[1].replace('_', '.'))

    fun expectedVersions(starting: String, packaged: List<String> = packagedNames()): List<String> {
        val inventory = packaged.sortedBy(::version)
        require(inventory.map(::version).distinct().size == inventory.size) { "Duplicate migration version" }
        require(required.map(::version) == (23..79).map { MigrationVersion.fromVersion("175.$it") }) { "Required historical inventory changed order or has gaps" }
        require(inventory.containsAll(required)) { "Required migration missing or renamed: ${required - inventory.toSet()}" }
        val lastRequired = version(required.last())
        val firstRequired = version(required.first())
        require(inventory.filter { version(it) in firstRequired..lastRequired } == required) { "Historical migration order changed" }
        val minors = inventory.mapNotNull { Regex("V175_([0-9]+)__.+\\.sql").matchEntire(it)?.groupValues?.get(1)?.toInt() }.filter { it >= 23 }
        require(minors == (23..minors.last()).toList()) { "Forward migration inventory has gaps" }
        if (starting == "latest") return emptyList()
        val baseline = MigrationVersion.fromVersion(starting)
        require(inventory.any { version(it) == baseline }) { "Starting migration is absent" }
        return inventory.filter { version(it) > baseline }.map { version(it).toString() }
    }

    fun assertUpgrade(starting: String, result: MigrateResult) {
        val expected = expectedVersions(starting)
        assertThat(result.migrationsExecuted).describedAs("packaged migration delta after %s", starting).isEqualTo(expected.size)
        assertThat(result.migrations.map { it.version }).describedAs("executed migration order after %s", starting)
            .containsExactlyElementsOf(expected)
    }

    private fun packagedNames(): List<String> = PathMatchingResourcePatternResolver()
        .getResources("classpath*:db/migration/V*.sql").groupBy { requireNotNull(it.filename) }.map { (name, copies) ->
            val hashes = copies.map { resource -> resource.inputStream.use { input ->
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))
            } }.distinct()
            require(hashes.size == 1) { "Conflicting classpath copies of $name" }
            name
        }
}
