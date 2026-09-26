package com.duluin.ftth.inventory

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object WarehouseDraftUpgradeProcess {
    const val PIN = "1d14f4b9aebcd1c1a9e8c84041259ff56a422005"
    private val mapper = jacksonObjectMapper()

    fun seed(database: WarehouseSchemaDatabase, family: String, target: String): JsonNode {
        check(System.getenv("WAREHOUSE_QA") == "true")
        val descriptor = Path.of(requireNotNull(System.getenv("WAREHOUSE_DRAFT_UPGRADE_DESCRIPTOR")) {
            "Prepare the pinned warehouse draft bootstrap with scripts/warehouse/qa.sh server"
        })
        val manifest = mapper.readTree(Files.readString(descriptor))
        check(manifest.path("pinnedApplication").asString() == PIN)
        val classpathFile = descriptor.parent.resolve("classpath.txt")
        check(sha256(Files.readAllBytes(classpathFile)) == manifest.path("classpathSha256").asString())
        val classpath = Files.readString(classpathFile).trim()
        check(classpath.isNotBlank() && !classpath.contains('\n'))
        val reportRoot = Path.of(requireNotNull(System.getenv("WAREHOUSE_DRAFT_UPGRADE_REPORT_DIR")))
        val folder = Files.createTempDirectory(reportRoot, "$family-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val output = folder.resolve("seed.json")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx768m", "-cp", classpath, "com.duluin.ftth.inventory.WarehouseDraftUpgradeSeed",
            output.toString(), database.url, target, family)
            .redirectErrorStream(true).redirectOutput(folder.resolve("process.log").toFile()).start()
        try {
            check(process.waitFor(180, TimeUnit.SECONDS)) { "Pinned draft bootstrap timed out; private report: $folder" }
            check(process.exitValue() == 0 && Files.isRegularFile(output)) { "Pinned draft bootstrap failed; private report: $folder" }
            val bytes = Files.readAllBytes(output)
            Files.writeString(folder.resolve("verification.json"), mapper.writeValueAsString(mapOf(
                "family" to family, "schemaBefore" to target, "pinnedApplication" to PIN,
                "seedSha256" to sha256(bytes), "bootstrapDescriptorSha256" to sha256(Files.readAllBytes(descriptor)),
                "processExitCode" to process.exitValue(), "oldProcessClosedBeforeCurrentMigration" to true,
            )))
            return mapper.readTree(bytes)
        } finally {
            if (process.isAlive) { process.destroyForcibly(); check(process.waitFor(15, TimeUnit.SECONDS)) }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
