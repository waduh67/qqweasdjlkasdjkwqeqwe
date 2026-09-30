package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerAssetHandoverEndpointIT : CustomerAssetOwnershipFixture() {
    enum class Denial(val code: String) {
        // These historical edits break the source graph; validated custody rejects
        // before the ordinary pending-state revision check.
        CLOSED("SOURCE_NOT_VERIFIED"), INACTIVE("SOURCE_NOT_VERIFIED"), UNRESOLVED_ORIGIN("SOURCE_NOT_VERIFIED"),
        QUARANTINED("SOURCE_NOT_VERIFIED"), NON_INSTALLED("SOURCE_NOT_VERIFIED"),
        WRONG_CUSTOMER("SOURCE_NOT_VERIFIED"), WRONG_WORK_ORDER("SOURCE_NOT_VERIFIED"),
        WRONG_ASSET("SOURCE_NOT_VERIFIED"), FROZEN_MODE("SOURCE_NOT_VERIFIED"),
    }

    companion object {
        private val database by lazy { WarehouseSchemaDatabase() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
    }
    @AfterAll fun closeDatabase() { database.close() }

    @ParameterizedTest
    @EnumSource(Denial::class)
    fun `handover HTTP rejects inadmissible owner state without any effects`(denial: Denial) {
        val case = ownershipCase("SALE")
        val stock = fixture(case.installation.receipt.stock.token)
        val guards = guardFingerprint(case)
        val customer = if (denial == Denial.WRONG_CUSTOMER) otherCustomer(case) else case.installation.customer
        if (denial != Denial.WRONG_CUSTOMER) stageHistoricalState(case, denial)
        assertThat(guardFingerprint(case)).describedAs("all guards restored before HTTP").isEqualTo(guards)
        val before = effects(case)
        val revision = if (denial == Denial.CLOSED) 1 else 0

        val response = request("POST", "/api/customers/$customer/assets/handover", case.installation.receipt.receiver.first,
            """{"assignmentId":"${case.installation.operation}","expectedRevision":$revision,"expectedTitleRevision":0,"evidenceId":"${case.signature}"}""", "endpoint-denial")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo(denial.code)
        assertThat(effects(case)).isEqualTo(before)
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_handover WHERE assignment_id='${case.installation.operation}'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_recovery_obligation WHERE assignment_id='${case.installation.operation}'")).isEqualTo("0")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["LOAN", "SALE"])
    fun `valid handover HTTP controls still work with every production guard enabled`(mode: String) {
        val case = ownershipCase(mode)

        val response = accept(case)

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(title(case)).isEqualTo(if (mode == "SALE") "SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1" else "LOAN|ISP|ISP|CUSTOMER_INSTALLED|1|1")
    }

    private fun otherCustomer(case: OwnershipCase): UUID {
        val token = case.installation.receipt.stock.token
        val response = request("POST", "/api/customers", token,
            """{"code":"OTHER","name":"Other customer","address":"Test","location":{"longitude":106.9,"latitude":-6.2},"areaId":"${area(token)}"}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return UUID.fromString(mapper.readTree(response.contentAsString).path("id").asString())
    }

    private fun stageHistoricalState(case: OwnershipCase, denial: Denial) {
        val stock = fixture(case.installation.receipt.stock.token)
        val asset = case.installation.receipt.input.lines.single().stockIdentityId
        val otherAsset = stock.transaction { scalar("SELECT id FROM inventory_serialized_asset WHERE id<>'$asset' LIMIT 1") }
        val otherWorkOrder = if (denial == Denial.WRONG_WORK_ORDER) {
            workOrder(case.installation.receipt.stock.token, "PSB", case.installation.customer.toString())
        } else case.installation.receipt.workOrder
        val statements = when (denial) {
            Denial.CLOSED -> listOf(
                "UPDATE inventory_asset_assignment SET ended_at=clock_timestamp(),revision=1 WHERE id='${case.installation.operation}'",
                """INSERT INTO inventory_asset_assignment_history(tenant_id,assignment_id,revision,snapshot)
                    SELECT tenant_id,id,revision,to_jsonb(entry) FROM inventory_asset_assignment entry WHERE id='${case.installation.operation}'""")
            Denial.INACTIVE -> listOf("UPDATE inventory_asset_assignment SET warehouse_admission='LEGACY_UNRESOLVED' WHERE id='${case.installation.operation}'")
            Denial.UNRESOLVED_ORIGIN -> listOf("UPDATE inventory_serialized_asset SET warehouse_admission='LEGACY_UNRESOLVED' WHERE id='$asset'")
            Denial.QUARANTINED -> listOf("UPDATE inventory_serialized_asset SET condition='QUARANTINE' WHERE id='$asset'",
                "UPDATE inventory_balance_projection SET condition='QUARANTINE' WHERE stock_identity_id='$asset' AND quantity_base>0")
            Denial.NON_INSTALLED -> listOf("UPDATE inventory_serialized_asset SET status='QUARANTINE' WHERE id='$asset'",
                "UPDATE inventory_balance_projection SET status='QUARANTINE' WHERE stock_identity_id='$asset' AND quantity_base>0")
            Denial.WRONG_WORK_ORDER -> listOf("UPDATE inventory_asset_assignment SET work_order_id='$otherWorkOrder' WHERE id='${case.installation.operation}'")
            Denial.WRONG_ASSET -> listOf("UPDATE inventory_asset_assignment SET asset_id='$otherAsset' WHERE id='${case.installation.operation}'")
            Denial.FROZEN_MODE -> listOf("""UPDATE inventory_receipt_intake SET snapshot=jsonb_set(snapshot::jsonb,'{lines}',
                (SELECT jsonb_agg(jsonb_set(item,'{sku,allowedOwnershipModes}','["LOAN"]'::jsonb)) FROM jsonb_array_elements(snapshot::jsonb->'lines') item))::text
                WHERE id=(SELECT document_id FROM inventory_document_line WHERE id=(SELECT origin_document_line_id FROM inventory_serialized_asset WHERE id='$asset'))""",
                "UPDATE inventory_receipt_intake SET content_hash=encode(sha256(convert_to(snapshot,'UTF8')),'hex') WHERE tenant_id='${stock.tenant}'")
            Denial.WRONG_CUSTOMER -> error("Customer mismatch uses the public route")
        }
        val tables = listOf("inventory_asset_assignment", "inventory_serialized_asset", "inventory_balance_projection", "inventory_receipt_intake")
        database.ownerFixture { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL app.tenant_id='${stock.tenant}'")
                tables.forEach { statement.execute("ALTER TABLE $it DISABLE TRIGGER USER") }
                statements.forEach { statement.execute(it) }
                tables.forEach { statement.execute("ALTER TABLE $it ENABLE TRIGGER USER") }
            }
            connection.commit()
        }
        stock.transaction {
            when (denial) {
                Denial.CLOSED -> assertThat(scalar("SELECT (ended_at IS NOT NULL AND revision=1)::text FROM inventory_asset_assignment WHERE id='${case.installation.operation}'")).isEqualTo("true")
                Denial.INACTIVE -> assertThat(scalar("SELECT warehouse_admission FROM inventory_asset_assignment WHERE id='${case.installation.operation}'")).isEqualTo("LEGACY_UNRESOLVED")
                Denial.UNRESOLVED_ORIGIN -> assertThat(scalar("SELECT warehouse_admission FROM inventory_serialized_asset WHERE id='$asset'")).isEqualTo("LEGACY_UNRESOLVED")
                Denial.QUARANTINED -> assertThat(scalar("SELECT condition FROM inventory_serialized_asset WHERE id='$asset'")).isEqualTo("QUARANTINE")
                Denial.NON_INSTALLED -> assertThat(scalar("SELECT status FROM inventory_serialized_asset WHERE id='$asset'")).isEqualTo("QUARANTINE")
                Denial.WRONG_WORK_ORDER -> assertThat(scalar("SELECT work_order_id FROM inventory_asset_assignment WHERE id='${case.installation.operation}'")).isEqualTo(otherWorkOrder)
                Denial.WRONG_ASSET -> assertThat(scalar("SELECT asset_id FROM inventory_asset_assignment WHERE id='${case.installation.operation}'")).isEqualTo(otherAsset)
                Denial.FROZEN_MODE -> assertThat(scalar("""SELECT count(*) FROM inventory_receipt_intake,
                    LATERAL jsonb_array_elements(snapshot::jsonb->'lines') item WHERE jsonb_exists(item#>'{sku,allowedOwnershipModes}','SALE')""")).isEqualTo("0")
                Denial.WRONG_CUSTOMER -> error("Customer mismatch uses the public route")
            }
        }
    }

    private fun guardFingerprint(case: OwnershipCase): String = fixture(case.installation.receipt.stock.token).transaction {
        scalar("""SELECT md5(string_agg(relation.relname||trigger.tgname||trigger.tgenabled::text||routine.prosrc,'|' ORDER BY relation.relname,trigger.tgname))
            FROM pg_trigger trigger JOIN pg_class relation ON relation.oid=trigger.tgrelid JOIN pg_proc routine ON routine.oid=trigger.tgfoid
            WHERE relation.relnamespace=current_schema()::regnamespace AND NOT trigger.tgisinternal""")
    }

    private fun effects(case: OwnershipCase): Map<String, String> = fixture(case.installation.receipt.stock.token).transaction {
        listOf("inventory_asset_assignment", "inventory_asset_assignment_history", "inventory_serialized_asset", "inventory_balance_projection",
            "inventory_asset_handover", "inventory_asset_acceptance", "inventory_asset_acceptance_origin", "inventory_asset_recovery_obligation",
            "inventory_asset_title_request", "inventory_asset_title_transfer", "inventory_asset_recovery_transition", "inventory_document",
            "inventory_document_line", "inventory_operation", "inventory_movement", "inventory_movement_leg", "inventory_outbox",
            "customer_asset_installation", "onu", "inventory_receipt_intake").associateWith { table ->
            scalar("SELECT md5(coalesce(jsonb_agg(to_jsonb(entry) ORDER BY to_jsonb(entry)::text)::text,'[]')) FROM $table entry WHERE tenant_id='$tenant'")
        }
    }
}
