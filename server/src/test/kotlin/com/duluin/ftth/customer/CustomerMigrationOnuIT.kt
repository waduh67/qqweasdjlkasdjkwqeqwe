package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseMasterHttpFixture
import com.duluin.ftth.monitoring.application.port.outbound.DiscoveredOnuRepository
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnu
import com.duluin.ftth.onboarding.application.service.CustomerImportPromotionPort
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.contract.OnuOperationalStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.time.Instant
import java.util.UUID

class CustomerMigrationOnuIT : WarehouseMasterHttpFixture() {
    private fun plan(token: String): String {
        val response = request("POST", "/api/catalog/plans", token,
            """{"name":"Migration plan","price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }
    private fun body(token: String, plan: String, code: String, serial: String? = null) = mapper.writeValueAsString(mapOf(
        "operationKey" to "migration-$code", "customer" to mapOf("code" to code, "name" to code, "address" to "Test",
            "planId" to plan, "areaId" to area(token)), "status" to "ACTIVE", "onuSerial" to serial))
    private fun migrate(token: String, plan: String, code: String, serial: String? = null): String {
        val response = request("POST", "/api/onboarding/migrations", token, body(token, plan, code, serial))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString).path("customerId").asString()
    }
    private fun discover(token: String, serial: String) {
        val fixture = fixture(token)
        fixture.transaction { context.getBean(DiscoveredOnuRepository::class.java).save(
            DiscoveredOnu.discover(fixture.tenant, serial, null, "OLT-OLD", "PON1", "ONLINE", -20.0)) }
    }
    private fun link(token: String, customer: String, serial: String) = request("POST", "/api/onboarding/migrations/$customer/onus", token,
        mapper.writeValueAsString(mapOf("serialNumber" to serial)))

    @Test fun `link existing subscriber replays without installation or stock and accepts only new matching telemetry`() {
        val token = tenant(); val customer = migrate(token, plan(token), "OLD-1"); discover(token, "ONU-OLD-1")
        repeat(2) { assertThat(link(token, customer, " onu-old-1 ").status).isEqualTo(200) }
        val fixture = fixture(token)
        fixture.transaction {
            assertThat(scalar("SELECT count(*) FROM onu")).isEqualTo("1")
            assertThat(scalar("SELECT warehouse_admission FROM onu")).isEqualTo("MIGRATED")
            assertThat(scalar("SELECT installed_at IS NULL AND asset_id IS NULL AND assignment_id IS NULL FROM onu")).isEqualTo("t")
            assertThat(scalar("SELECT count(*) FROM inventory_identity_claim WHERE state='LEGACY_RESERVED'")).isEqualTo("1")
            assertThat(scalar("SELECT state FROM discovered_onu")).isEqualTo("PROVISIONED")
            for (table in listOf("inventory_movement", "inventory_serialized_asset", "inventory_asset_assignment", "work_order", "subscriber_access", "invoice"))
                assertThat(scalar("SELECT count(*) FROM $table")).describedAs(table).isEqualTo("0")
            val api = context.getBean(CustomerObservationApi::class.java)
            assertThat(api.resolveObservation("ONU-OLD-1", Instant.now().minusSeconds(3600)).episode).isNull()
            assertThat(api.resolveObservation("ONU-OLD-1", Instant.now(), ObservationPath(null, "OLT-OTHER", "PON1")).reason).isEqualTo("PATH_MISMATCH")
        }
        TenantContext.runAs(fixture.tenant) {
            context.getBean(MetricIngestionService::class.java).ingestReadings(fixture.tenant, listOf(
                OnuReading("ONU-OLD-1", "OLT-OLD", "PON1", OnuOperationalStatus.ONLINE, -19.5, null, null, null, Instant.now())))
        }
        val detail = mapper.readTree(request("GET", "/api/customers/$customer", token).contentAsString)
        assertThat(detail.path("onus").size()).isEqualTo(1)
        assertThat(detail.path("onus")[0].path("status").asString()).isEqualTo("ONLINE")
        fixture.transaction { assertThat(scalar("SELECT count(*) FROM onu_metric")).isEqualTo("1") }
        val sync = context.getBean(com.duluin.ftth.cpe.application.service.CpeSyncService::class.java)
        fun snapshot(at: Instant) = com.duluin.ftth.cpe.application.port.outbound.AcsDevice(
            "MIGRATED-CPE", "ONU-OLD-1", null, null, "Vendor", "ONU", null, null, at, observedFieldsAt = at)
        TenantContext.runAs(fixture.tenant) { sync.sync(listOf(snapshot(Instant.now().minusSeconds(3600)))) }
        fixture.transaction { assertThat(scalar("SELECT count(*) FROM cpe_device")).isEqualTo("0") }
        TenantContext.runAs(fixture.tenant) { sync.sync(listOf(snapshot(Instant.now()))) }
        fixture.transaction { assertThat(scalar("SELECT customer_id FROM cpe_device")).isEqualTo(customer) }
    }

    @Test fun `manual migration is atomic and serial cannot be claimed by another customer or tenant`() {
        val token = tenant(); val plan = plan(token); discover(token, "ONU-A")
        val first = migrate(token, plan, "OLD-A", "ONU-A")
        assertThat(migrate(token, plan, "OLD-A", "ONU-A")).isEqualTo(first)
        val conflict = request("POST", "/api/onboarding/migrations", token, body(token, plan, "OLD-B", "ONU-A"))
        assertThat(conflict.status).withFailMessage(conflict.contentAsString).isEqualTo(409)
        fixture(token).transaction { assertThat(scalar("SELECT count(*) FROM customer")).isEqualTo("1") }
        assertThat(link(tenant(), first, "ONU-A").status).isEqualTo(404)
        val missing = request("POST", "/api/onboarding/migrations", token, body(token, plan, "OLD-C", "NOT-DETECTED"))
        assertThat(missing.status).isEqualTo(409)
    }

    @Test fun `link and csv require device permissions and customer area visibility`() {
        val token = tenant(); val plan = plan(token); val customer = migrate(token, plan, "OLD-A"); discover(token, "ONU-A")
        val permissions = setOf("customer.customer.create", "customer.customer.update", "customer.subscription.update", "customer.customer.view")
        val (operator, operatorId) = user(token, permissions)
        assertThat(link(operator, customer, "ONU-A").status).isEqualTo(403)
        assertThat(request("POST", "/api/onboarding/migrations", operator, body(token, plan, "NO-ACCESS", "ONU-A")).status).isEqualTo(403)
        val (outside, _) = user(token, permissions + setOf("customer.onu.assign", "monitoring.provisioning.view"))
        assertThat(link(outside, customer, "ONU-A").status).isEqualTo(404)
        val csv = "name,address,package_name,customer_code,onu_serial\nCSV,Test,Migration plan,CSV-A,ONU-A\n"
        val me = mapper.readTree(request("GET", "/api/me", operator).contentAsString)
        assertThat(request("PUT", "/api/users/$operatorId/access", token, mapper.writeValueAsString(mapOf(
            "roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(token))))).status).isEqualTo(200)
        val denied = upload(operator, csv, area(token))
        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(403)
        val staged = mapper.readTree(upload(token, csv, area(token)).contentAsString)
        val deniedCommit = request("POST", "/api/onboarding/v1/import/customers/${staged.path("id").asString()}/commit?commitOperationKey=denied&commitHash=${staged.path("sha256").asString()}", operator)
        assertThat(deniedCommit.status).withFailMessage(deniedCommit.contentAsString).isEqualTo(403)
    }

    @Test fun `csv exposes serial in preview and links only successfully committed rows`() {
        val token = tenant(); plan(token); discover(token, "ONU-CSV")
        val csv = "name,address,package_name,customer_code,onu_serial\nCSV,Test,Migration plan,CSV-A,ONU-CSV\nBad,Test,Migration plan,CSV-B,MISSING\n"
        val uploaded = upload(token, csv, area(token))
        assertThat(uploaded.status).withFailMessage(uploaded.contentAsString).isEqualTo(202)
        val staged = mapper.readTree(uploaded.contentAsString)
        assertThat(staged.path("preview")[0].path("onuSerial").asString()).isEqualTo("ONU-CSV")
        val id = staged.path("id").asString()
        assertThat(request("POST", "/api/onboarding/v1/import/customers/$id/commit?commitOperationKey=commit&commitHash=${staged.path("sha256").asString()}", token).status).isEqualTo(200)
        val fixture = fixture(token)
        TenantContext.runAs(fixture.tenant) { context.getBean(CustomerImportPromotionPort::class.java).promoteOne() }
        fixture.transaction {
            assertThat(scalar("SELECT count(*) FROM customer")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM customer_migration_onu")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM work_order")).isEqualTo("0")
        }
        val result = mapper.readTree(request("GET", "/api/onboarding/v1/import/customers/$id", token).contentAsString).path("result")
        assertThat(result.path("created").asInt()).isEqualTo(1)
        assertThat(result.path("failed").asInt()).isEqualTo(1)
    }

    @Test fun `application SQL cannot bypass warehouse admission or rewrite migrated identity`() {
        val token = tenant(); val customer = migrate(token, plan(token), "GUARD"); discover(token, "ONU-GUARD")
        assertThat(link(token, customer, "ONU-GUARD").status).isEqualTo(200)
        val fixture = fixture(token)
        org.assertj.core.api.Assertions.assertThatThrownBy { fixture.transaction {
            scalar("INSERT INTO onu(id,tenant_id,customer_id,serial_number,warehouse_admission,started_at) VALUES (gen_random_uuid(),'${fixture.tenant}','$customer','BYPASS','MIGRATED',now()) RETURNING id")
        } }.hasStackTraceContaining("warehouse admission requires migration owner")
        org.assertj.core.api.Assertions.assertThatThrownBy { fixture.transaction {
            scalar("UPDATE onu SET started_at=started_at-interval '1 day' WHERE serial_number='ONU-GUARD' RETURNING id")
        } }.hasStackTraceContaining("migration observation identity and start are immutable")
        org.assertj.core.api.Assertions.assertThatThrownBy { fixture.transaction {
            scalar("UPDATE inventory_identity_claim SET state='RETIRED' WHERE canonical_value='ONU-GUARD' RETURNING id")
        } }.hasStackTraceContaining("reserved identity requires approved reconciliation")
    }
    private fun upload(token: String, csv: String, area: String?) = mvc.perform(multipart("/api/onboarding/v1/import/customers")
        .file(MockMultipartFile("file", "migration.csv", "text/csv", csv.toByteArray())).param("operationKey", UUID.randomUUID().toString())
        .apply { if (area != null) param("areaId", area) }.header("Authorization", "Bearer $token")).andReturn().response
}
