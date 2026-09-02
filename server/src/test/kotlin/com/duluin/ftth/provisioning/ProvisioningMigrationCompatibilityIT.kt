package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.adapter.outbound.persistence.NormalizedStateJsonCodec
import javax.sql.DataSource

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningMigrationCompatibilityIT {
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var normalizedStateCodec: NormalizedStateJsonCodec

    @Test
    fun `v127 downgrades legacy management evidence bound to another device`() {
        val schema = "task4_source_upgrade_${UuidV7.generate().toString().replace("-", "")}"
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            flyway(schema, "126").migrate()
            val tenantId = UuidV7.generate()
            val evidenceId = UuidV7.generate()
            val evidenceDeviceId = UuidV7.generate()
            val observedDeviceId = UuidV7.generate()
            val observationId = UuidV7.generate()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO $schema, public")
                    statement.execute("INSERT INTO tenant (id, slug, name) VALUES ('$tenantId', 'task4-source-upgrade', 'Task 4 Source Upgrade')")
                    statement.execute("SET app.tenant_id = '$tenantId'")
                    statement.execute(
                        """INSERT INTO provisioning_device_observation
                           (id, tenant_id, device_kind, device_id, normalized_state, observed_at)
                           VALUES ('$observationId', '$tenantId', 'BRAS', '$observedDeviceId', '{}', now())""",
                    )
                    statement.execute(
                        """INSERT INTO provisioning_management_safety_evidence
                           (id, tenant_id, device_kind, device_id, protected_vlan_ranges, protected_ip_prefixes,
                            protected_vrfs, protected_interface_roles, protected_collector_paths, protected_oob_routes,
                            available_oob_routes, observed_at, valid_until, complete, source_type,
                            device_observation_source_id)
                           VALUES ('$evidenceId', '$tenantId', 'BRAS', '$evidenceDeviceId', '', '', '', '', '', '', '',
                            now(), now() + interval '1 hour', true, 'DEVICE_OBSERVATION', '$observationId')""",
                    )
                }
            }

            flyway(schema).migrate()

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO $schema, public")
                    statement.execute("SET app.tenant_id = '$tenantId'")
                    statement.executeQuery(
                        "SELECT complete, source_type, topology_source_id, device_observation_source_id FROM provisioning_management_safety_evidence WHERE id = '$evidenceId'",
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getBoolean(1)).isFalse()
                        assertThat(result.getString(2)).isNull()
                        assertThat(result.getObject(3)).isNull()
                        assertThat(result.getObject(4)).isNull()
                    }
                    statement.executeQuery(
                        """SELECT convalidated FROM pg_constraint
                           WHERE conname = 'fk_provisioning_management_observation_source'
                             AND conrelid = '$schema.provisioning_management_safety_evidence'::regclass""",
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getBoolean(1)).isTrue()
                    }
                }
            }
        } finally {
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    @Test
    fun `v125 downgrades unbound legacy certification to fail closed provisional evidence`() {
        val schema = "task4_upgrade_${UuidV7.generate().toString().replace("-", "")}"
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            flyway(schema, "124").migrate()
            val tenantId = UuidV7.generate()
            val certificationId = UuidV7.generate()
            val certifiedAt = java.time.Instant.parse("2026-09-02T12:00:00Z")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO $schema, public")
                    statement.execute("INSERT INTO tenant (id, slug, name) VALUES ('$tenantId', 'task4-upgrade', 'Task 4 Upgrade')")
                    statement.execute("SET app.tenant_id = '$tenantId'")
                    statement.execute(
                        """INSERT INTO provisioning_adapter_certification
                           (id, tenant_id, device_kind, device_id, vendor, model, firmware, transport,
                            operation_class, status, valid_until, evidence_id, certified_by, certified_at)
                           VALUES ('$certificationId', '$tenantId', 'BRAS', '${UuidV7.generate()}', 'MIKROTIK',
                            'CCR2004', '7.20.2', 'HTTPS_REST', 'ENSURE_PPPOE_TERMINATION', 'CERTIFIED',
                            '${certifiedAt.plusSeconds(3600)}', '${UuidV7.generate()}', '${UuidV7.generate()}', '$certifiedAt')""",
                    )
                }
            }

            flyway(schema).migrate()

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO $schema, public")
                    statement.execute("SET app.tenant_id = '$tenantId'")
                    statement.executeQuery(
                        "SELECT status, evidence_id IS NULL, valid_until = certified_at FROM provisioning_adapter_certification WHERE id = '$certificationId'",
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getString(1)).isEqualTo("PROVISIONAL")
                        assertThat(result.getBoolean(2)).isTrue()
                        assertThat(result.getBoolean(3)).isTrue()
                    }
                }
            }
        } finally {
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    @Test
    fun `v120 upgrades legacy plan hashes and preserves valid legacy normalized rows`() {
        val schema = "task1_upgrade_${UuidV7.generate().toString().replace("-", "")}"
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            flyway(schema, "119").migrate()
            val tenantId = UuidV7.generate()
            val poolId = UuidV7.generate()
            val profileId = UuidV7.generate()
            val intentId = UuidV7.generate()
            val planId = UuidV7.generate()
            val stepId = UuidV7.generate()
            val observationId = UuidV7.generate()
            val knownLegacyObservationId = UuidV7.generate()
            val nestedLegacyObservationId = UuidV7.generate()
            val executionId = UuidV7.generate()
            val deviceId = UuidV7.generate()
            val legacyAttributes = mapOf("legacyFlag" to "enabled", "\uE000" to "bmp", "\uD800\uDC00" to "supplementary")
            val legacyHash = legacyHash(deviceId, legacyAttributes)

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO $schema, public")
                    statement.execute("INSERT INTO tenant (id, slug, name) VALUES ('$tenantId', 'upgrade', 'Upgrade')")
                    statement.execute("SET app.tenant_id = '$tenantId'")
                    statement.execute("INSERT INTO provisioning_vlan_pool (id, tenant_id, name, vlan_start, vlan_end) VALUES ('$poolId', '$tenantId', 'Pool', 100, 199)")
                    statement.execute("INSERT INTO provisioning_segment_profile (id, tenant_id, name, pool_id) VALUES ('$profileId', '$tenantId', 'Profile', '$poolId')")
                    statement.execute("INSERT INTO provisioning_service_intent (id, tenant_id, subscription_id, segment_profile_id, encapsulation, status) VALUES ('$intentId', '$tenantId', '${UuidV7.generate()}', '$profileId', 'SINGLE_TAG', 'ACTIVE')")
                    statement.execute("INSERT INTO provisioning_plan (id, tenant_id, intent_id, revision, status, content_hash) VALUES ('$planId', '$tenantId', '$intentId', 1, 'GENERATED', '$legacyHash')")
                    statement.execute("INSERT INTO provisioning_step (id, tenant_id, plan_id, step_order, device_kind, device_id, operation) VALUES ('$stepId', '$tenantId', '$planId', 1, 'ROUTER', '$deviceId', 'ENSURE_TAGGED_VLAN')")
                    statement.execute("INSERT INTO provisioning_step_attribute (id, tenant_id, step_id, attribute_key, attribute_value) VALUES ('${UuidV7.generate()}', '$tenantId', '$stepId', 'legacyFlag', 'enabled')")
                    statement.execute("INSERT INTO provisioning_step_attribute (id, tenant_id, step_id, attribute_key, attribute_value) VALUES ('${UuidV7.generate()}', '$tenantId', '$stepId', '${"\uE000"}', 'bmp')")
                    statement.execute("INSERT INTO provisioning_step_attribute (id, tenant_id, step_id, attribute_key, attribute_value) VALUES ('${UuidV7.generate()}', '$tenantId', '$stepId', '${"\uD800\uDC00"}', 'supplementary')")
                    statement.execute("UPDATE provisioning_plan SET status = 'VALIDATED' WHERE id = '$planId'")
                    statement.execute("INSERT INTO provisioning_device_observation (id, tenant_id, device_kind, device_id, normalized_state, observed_at) VALUES ('$observationId', '$tenantId', 'ROUTER', '$deviceId', '{\"legacyFlag\":true}', now())")
                    statement.execute("INSERT INTO provisioning_device_observation (id, tenant_id, device_kind, device_id, normalized_state, observed_at) VALUES ('$knownLegacyObservationId', '$tenantId', 'ROUTER', '$deviceId', '{\"configured\":\"yes\"}', now())")
                    statement.execute("INSERT INTO provisioning_device_observation (id, tenant_id, device_kind, device_id, normalized_state, observed_at) VALUES ('$nestedLegacyObservationId', '$tenantId', 'ROUTER', '$deviceId', '{\"interfaces\":[{\"name\":\"ether1\",\"legacyFlag\":true}]}', now())")
                    statement.execute("INSERT INTO provisioning_execution (id, tenant_id, plan_id, idempotency_key, status) VALUES ('$executionId', '$tenantId', '$planId', 'legacy-execution', 'QUEUED')")
                }
            }

            flyway(schema).migrate()

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO $schema, public")
                    statement.execute("SET app.tenant_id = '$tenantId'")
                    statement.executeQuery("SELECT content_hash = provisioning_calculate_plan_hash(id, tenant_id) FROM provisioning_plan WHERE id = '$planId'").use {
                        assertThat(it.next()).isTrue()
                        assertThat(it.getBoolean(1)).isTrue()
                    }
                    val migratedHash = statement.executeQuery("SELECT content_hash FROM provisioning_plan WHERE id = '$planId'").use {
                        assertThat(it.next()).isTrue()
                        it.getString(1)
                    }
                    val legacyStep = ProvisionStep.rehydrate(
                        stepId,
                        1,
                        DeviceReference(DeviceKind.ROUTER, deviceId),
                        ProvisionOperation.ENSURE_TAGGED_VLAN,
                        legacyAttributes,
                    )
                    assertThat(
                        ProvisionPlan.rehydrate(planId, tenantId, intentId, 1, listOf(legacyStep), PlanStatus.VALIDATED, migratedHash)
                            .contentHash,
                    ).isEqualTo(migratedHash)
                    statement.executeQuery("SELECT normalized_state = '{\"legacyFlag\":true}'::jsonb FROM provisioning_device_observation WHERE id = '$observationId'").use {
                        assertThat(it.next()).isTrue()
                        assertThat(it.getBoolean(1)).isTrue()
                    }
                    listOf(knownLegacyObservationId, nestedLegacyObservationId).forEach { legacyId ->
                        val persistedJson = statement.executeQuery(
                            "SELECT normalized_state::text FROM provisioning_device_observation WHERE id = '$legacyId'",
                        ).use {
                            assertThat(it.next()).isTrue()
                            it.getString(1)
                        }
                        val decoded = normalizedStateCodec.decode(persistedJson)
                        assertThat(decoded.legacyPayload).isNotNull()
                        assertThat(
                            tools.jackson.databind.ObjectMapper().readTree(normalizedStateCodec.encode(decoded)),
                        ).isEqualTo(tools.jackson.databind.ObjectMapper().readTree(persistedJson))
                    }
                    statement.executeQuery("SELECT intent_id = '$intentId'::uuid FROM provisioning_execution WHERE id = '$executionId'").use {
                        assertThat(it.next()).isTrue()
                        assertThat(it.getBoolean(1)).isTrue()
                    }
                    statement.executeQuery(
                        """SELECT convalidated FROM pg_constraint
                           WHERE conname = 'ck_provisioning_observation_normalized'
                             AND conrelid = '$schema.provisioning_device_observation'::regclass""",
                    ).use {
                        assertThat(it.next()).isTrue()
                        assertThat(it.getBoolean(1)).isFalse()
                    }
                    assertThatThrownBy {
                        statement.execute(
                            "INSERT INTO provisioning_device_observation (id, tenant_id, device_kind, device_id, normalized_state, observed_at) VALUES ('${UuidV7.generate()}', '$tenantId', 'ROUTER', '$deviceId', '{\"legacyFlag\":true}', now())",
                        )
                    }.isInstanceOf(java.sql.SQLException::class.java)
                }
            }
        } finally {
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    @Test
    fun `v120 fails closed when a legacy normalized value contains device instructions`() {
        val schema = "task1_unsafe_${UuidV7.generate().toString().replace("-", "")}"
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            flyway(schema, "119").migrate()
            val tenantId = UuidV7.generate()
            val observationId = UuidV7.generate()
            val deviceId = UuidV7.generate()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO $schema, public")
                    statement.execute("INSERT INTO tenant (id, slug, name) VALUES ('$tenantId', 'unsafe-upgrade', 'Unsafe Upgrade')")
                    statement.execute("SET app.tenant_id = '$tenantId'")
                    statement.execute(
                        "INSERT INTO provisioning_device_observation (id, tenant_id, device_kind, device_id, normalized_state, observed_at) VALUES ('$observationId', '$tenantId', 'ROUTER', '$deviceId', '{\"name\":\"/interface vlan add\"}', now())",
                    )
                }
            }

            assertThatThrownBy { flyway(schema).migrate() }
                .hasMessageContaining("LEGACY_NORMALIZED_STATE_UNSAFE")
        } finally {
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }

    private fun flyway(schema: String, target: String? = null): Flyway {
        val configuration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
        if (target != null) configuration.target(MigrationVersion.fromVersion(target))
        return configuration.load()
    }

    private fun legacyHash(deviceId: java.util.UUID, attributes: Map<String, String>): String {
        val canonical = buildString {
            append("1|ROUTER|").append(deviceId).append("|ENSURE_TAGGED_VLAN")
            attributes.toSortedMap().forEach { (key, value) -> append('|').append(key).append('=').append(value) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
