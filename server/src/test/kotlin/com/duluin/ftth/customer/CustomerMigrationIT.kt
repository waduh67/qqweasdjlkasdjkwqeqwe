package com.duluin.ftth.customer

import com.duluin.ftth.billing.application.service.InvoiceGenerator
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.WarehouseMasterHttpFixture
import com.duluin.ftth.onboarding.application.service.CustomerImportPromotionPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.time.LocalDate
import java.util.UUID
import tools.jackson.databind.JsonNode

class CustomerMigrationIT : WarehouseMasterHttpFixture() {
    @Test fun `csv billing preview and imported schedules honor each row before optional defaults`() {
        val token = tenant()
        plan(token)
        val month = LocalDate.now().plusMonths(1).withDayOfMonth(1)
        for (useDefaults in listOf(false, true)) {
            val prefix = if (useDefaults) "DEFAULT" else "CSV"
            val csv = "name,address,package_name,customer_code,first_invoice_on,next_billing\n" +
                "Ani,Test,Existing service,$prefix-A,${month.withDayOfMonth(5)},20\n" +
                "Budi,Test,Existing service,$prefix-B,${month.withDayOfMonth(15)},\n" +
                "Cici,Test,Existing service,$prefix-C,,25\n" +
                "Dedi,Test,Existing service,$prefix-D,,\n"
            val uploaded = mvc.perform(multipart("/api/onboarding/v1/import/customers")
                .file(MockMultipartFile("file", "billing.csv", "text/csv", csv.toByteArray()))
                .param("operationKey", "billing-$prefix").param("areaId", area(token))
                .apply { if (useDefaults) { param("firstInvoiceOn", month.toString()); param("billingDay", "1") } }
                .header("Authorization", "Bearer $token")).andReturn().response
            assertThat(uploaded.status).withFailMessage(uploaded.contentAsString).isEqualTo(202)
            val batch = mapper.readTree(uploaded.contentAsString)
            val preview = batch.path("preview")
            val expected = listOf(
                month.withDayOfMonth(5) to 20,
                month.withDayOfMonth(15) to if (useDefaults) 1 else 15,
                (month.takeIf { useDefaults }) to 25.takeIf { useDefaults },
                (month.takeIf { useDefaults }) to 1.takeIf { useDefaults },
            )
            expected.forEachIndexed { index, (first, day) ->
                assertThat(preview[index].path("firstInvoiceOn").takeUnless { it.isNull }?.asString()).isEqualTo(first?.toString())
                assertThat(preview[index].path("billingDay").takeUnless { it.isNull }?.asInt()).isEqualTo(day)
            }
            val id = batch.path("id").asString()
            assertThat(request("POST", "/api/onboarding/v1/import/customers/$id/commit?commitOperationKey=commit-$prefix&commitHash=${batch.path("sha256").asString()}", token).status).isEqualTo(200)
            val stock = fixture(token)
            TenantContext.runAs(stock.tenant) { context.getBean(CustomerImportPromotionPort::class.java).promoteOne() }
            val completed = mapper.readTree(request("GET", "/api/onboarding/v1/import/customers/$id", token).contentAsString)
            assertThat(completed.path("result").path("created").asInt()).withFailMessage(completed.toString()).isEqualTo(4)
            stock.transaction {
                expected.forEachIndexed { index, (first, day) ->
                    val code = "$prefix-${'A' + index}"
                    val customer = scalar("SELECT id FROM customer WHERE code='$code'")
                    assertThat(scalar("SELECT billing_managed FROM subscription WHERE customer_id='$customer'"))
                        .isEqualTo(if (first == null) "f" else "t")
                    if (first != null) {
                        assertThat(scalar("SELECT billing_starts_on FROM subscription WHERE customer_id='$customer'")).isEqualTo(first.toString())
                        assertThat(scalar("SELECT billing_day_of_month FROM subscription WHERE customer_id='$customer'")).isEqualTo(day.toString())
                    }
                }
                assertThat(scalar("SELECT count(*) FROM invoice")).isEqualTo("0")
            }
        }
    }

    private fun plan(token: String): String {
        val response = request("POST", "/api/catalog/plans", token,
            """{"name":"Existing service","price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }
    private fun body(token: String, plan: String, username: String = "existing-user", status: String = "ACTIVE", firstInvoice: LocalDate? = null, billingDay: Int? = null): String = mapper.writeValueAsString(mapOf(
        "operationKey" to "migration-$username", "customer" to mapOf("code" to "OLD-$username", "name" to "Existing subscriber", "address" to "Jl. Melati", "phone" to null,
            "email" to null, "location" to null, "areaId" to area(token), "planId" to plan, "monthlyFeeOverride" to 125000),
        "status" to status, "installationDate" to "2020-01-15", "firstInvoiceOn" to firstInvoice?.toString(), "billingDay" to billingDay,
        "username" to username, "password" to "legacy-password", "authType" to "PPPOE",
    ))
    private fun migrate(token: String, body: String): JsonNode {
        val response = request("POST", "/api/onboarding/migrations", token, body)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(response.contentAsString).doesNotContain("legacy-password", "secretCiphertext")
        return mapper.readTree(response.contentAsString)
    }

    @Test fun `active migration preserves commercial state and replays without work order radius or billing`() {
        val token = tenant()
        val payload = body(token, plan(token))
        val first = migrate(token, payload)
        assertThat(migrate(token, payload).path("customerId").asString()).isEqualTo(first.path("customerId").asString())
        assertThat(request("POST", "/api/onboarding/migrations", token, payload.replace("125000", "120000")).status).isEqualTo(409)
        assertThat(request("POST", "/api/onboarding/migrations", token, payload.replace("migration-existing-user", "another-operation")).status).isEqualTo(409)
        val detail = mapper.readTree(request("GET", "/api/customers/${first.path("customerId").asString()}", token).contentAsString)
        assertThat(detail.path("status").asString()).isEqualTo("ACTIVE")
        assertThat(detail.path("subscription").path("status").asString()).isEqualTo("ACTIVE")
        assertThat(detail.path("subscription").path("monthlyFee").decimalValue()).isEqualByComparingTo("125000")
        assertThat(detail.path("subscription").path("activatedAt").asString()).startsWith("2020-01-15")
        assertThat(detail.path("location").isNull).isTrue()
        assertThat(first.path("billingManaged").asBoolean()).isFalse()
        val exported = request("GET", "/api/onboarding/export/customers", token)
        assertThat(exported.status).isEqualTo(200)
        assertThat(exported.contentAsString).contains("OLD-EXISTING-USER", "Existing subscriber", "125000").doesNotContain("legacy-password")
        val stock = fixture(token)
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM customer")).isEqualTo("1")
            val ciphertext = scalar("SELECT secret_ciphertext FROM customer_migration")
            assertThat(ciphertext).isNotEqualTo("legacy-password").isNotBlank()
            assertThat(context.getBean(com.duluin.ftth.common.security.SecretCipher::class.java).decrypt(ciphertext)).isEqualTo("legacy-password")
            assertThat(context.getBean(InvoiceGenerator::class.java).generateFor(stock.tenant, LocalDate.now().plusMonths(2))).isZero()
            for (table in listOf("subscriber_access", "bng_action", "work_order", "migration_fulfillment_inbox", "invoice")) assertThat(scalar("SELECT count(*) FROM $table")).describedAs(table).isEqualTo("0")
        }
    }

    @Test fun `future billing boundary and short month day31 generate full single invoices`() {
        val token = tenant()
        val first = LocalDate.of(LocalDate.now().year + 1, 1, 31)
        migrate(token, body(token, plan(token), firstInvoice = first, billingDay = 31))
        val stock = fixture(token)
        stock.transaction {
            val generator = context.getBean(InvoiceGenerator::class.java)
            assertThat(generator.generateFor(stock.tenant, first.minusDays(1))).isZero()
            assertThat(generator.generateFor(stock.tenant, first)).isEqualTo(1)
            assertThat(generator.generateFor(stock.tenant, first)).isZero()
            assertThat(scalar("SELECT amount FROM invoice").toBigDecimal()).isEqualByComparingTo("125000")
            val february = first.plusMonths(1)
            assertThat(generator.generateFor(stock.tenant, february.minusDays(1))).isZero()
            assertThat(generator.generateFor(stock.tenant, february)).isEqualTo(1)
            assertThat(generator.generateFor(stock.tenant, first.plusMonths(2).withDayOfMonth(30))).isZero()
            assertThat(generator.generateFor(stock.tenant, first.plusMonths(2).withDayOfMonth(31))).isEqualTo(1)
        }
    }

    @Test fun `first invoice date is honored even when recurring billing day is later`() {
        val token = tenant()
        val first = LocalDate.now().plusMonths(1).withDayOfMonth(15)
        migrate(token, body(token, plan(token), firstInvoice = first, billingDay = 25))
        val stock = fixture(token)
        stock.transaction {
            val generator = context.getBean(InvoiceGenerator::class.java)
            assertThat(generator.generateFor(stock.tenant, first.minusDays(1))).isZero()
            assertThat(generator.generateFor(stock.tenant, first)).isEqualTo(1)
            assertThat(generator.generateFor(stock.tenant, first.plusMonths(1).withDayOfMonth(24))).isZero()
            assertThat(generator.generateFor(stock.tenant, first.plusMonths(1).withDayOfMonth(25))).isEqualTo(1)
        }
    }

    @Test fun `suspended subscriber can start billing later without reactivation and command is replayable`() {
        val token = tenant()
        val result = migrate(token, body(token, plan(token), status = "SUSPENDED"))
        val customerId = result.path("customerId").asString()
        val first = LocalDate.now().plusMonths(1).withDayOfMonth(1)
        val payload = """{"firstInvoiceOn":"$first","billingDay":1}"""
        repeat(2) { assertThat(request("POST", "/api/onboarding/migrations/$customerId/billing", token, payload).status).isEqualTo(200) }
        fixture(token).transaction {
            assertThat(scalar("SELECT status FROM customer")).isEqualTo("SUSPENDED")
            assertThat(scalar("SELECT status FROM subscription")).isEqualTo("ISOLATED")
            assertThat(scalar("SELECT billing_managed FROM subscription")).isEqualTo("t")
            assertThat(scalar("SELECT count(*) FROM subscriber_access")).isEqualTo("0")
        }
    }

    @Test fun `radius preparation is explicit replayable and preserves isolated account state`() {
        val token = tenant()
        val migrated = migrate(token, body(token, plan(token), status = "SUSPENDED"))
        val customerId = migrated.path("customerId").asString()
        val nas = request("POST", "/api/bng/nas", token, """{"name":"Migration BRAS","vendor":"MIKROTIK","areaIds":["${area(token)}"]}""")
        assertThat(nas.status).withFailMessage(nas.contentAsString).isEqualTo(201)
        val nasId = mapper.readTree(nas.contentAsString).path("id").asString()
        val command = """{"nasId":"$nasId","username":"existing-user","authType":"PPPOE"}"""
        repeat(2) {
            val prepared = request("POST", "/api/onboarding/migrations/$customerId/radius", token, command)
            assertThat(prepared.status).withFailMessage(prepared.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(prepared.contentAsString).path("accessId").isNull).isFalse()
        }
        fixture(token).transaction {
            assertThat(scalar("SELECT count(*) FROM subscriber_access")).isEqualTo("1")
            assertThat(scalar("SELECT status FROM subscriber_access")).isEqualTo("ISOLATED")
            assertThat(scalar("SELECT secret_ciphertext IS NULL FROM customer_migration")).isEqualTo("t")
            assertThat(scalar("SELECT count(*) FROM work_order")).isEqualTo("0")
        }
    }

    @Test fun `migration respects tenant area and separate network permissions`() {
        val token = tenant()
        val plan = plan(token)
        val migrated = migrate(token, body(token, plan))
        val customerId = migrated.path("customerId").asString()
        val otherTenant = tenant()
        assertThat(request("GET", "/api/onboarding/migrations/$customerId", otherTenant).status).isEqualTo(404)
        assertThat(request("POST", "/api/onboarding/migrations", token, body(token, plan, "outside").replace(area(token), UUID.randomUUID().toString())).status).isEqualTo(400)
        val (operator, _) = user(token, setOf("customer.customer.create", "customer.subscription.update", "customer.customer.view"))
        assertThat(request("GET", "/api/onboarding/migrations/$customerId", operator).status).isEqualTo(404)
        val response = request("POST", "/api/onboarding/migrations/$customerId/radius", operator, """{"nasId":"${UUID.randomUUID()}","username":"existing-user"}""")
        assertThat(response.status).isEqualTo(403)
        assertThat(request("POST", "/api/onboarding/migrations", token, body(token, plan, "past", firstInvoice = LocalDate.now().minusDays(1))).status).isEqualTo(400)
    }

    @Test fun `staged csv promotion commits independent rows and retries cannot duplicate customers`() {
        val token = tenant()
        plan(token)
        val csv = "name,address,package_name,customer_code,service_status,monthly_fee\nAni,Test,Existing service,OLD-A,ACTIVE,125000\nBad,Test,Existing service,OLD-B,ACTIVE,-1\nBudi,Test,Existing service,OLD-C,SUSPENDED,120000\n"
        val upload = mvc.perform(multipart("/api/onboarding/v1/import/customers")
            .file(MockMultipartFile("file", "migration.csv", "text/csv", csv.toByteArray()))
            .param("operationKey", "stage-migration").param("areaId", area(token)).header("Authorization", "Bearer $token")).andReturn().response
        assertThat(upload.status).withFailMessage(upload.contentAsString).isEqualTo(202)
        val batch = mapper.readTree(upload.contentAsString)
        assertThat(batch.path("preview").size()).isEqualTo(3)
        assertThat(batch.path("preview").toString()).doesNotContain("password", "credential")
        val id = batch.path("id").asString()
        val url = "/api/onboarding/v1/import/customers/$id/commit?commitOperationKey=promote&commitHash=${batch.path("sha256").asString()}"
        assertThat(request("POST", url, token).status).isEqualTo(200)
        val stock = fixture(token)
        repeat(2) { TenantContext.runAs(stock.tenant) { context.getBean(CustomerImportPromotionPort::class.java).promoteOne() } }
        val completed = mapper.readTree(request("GET", "/api/onboarding/v1/import/customers/$id", token).contentAsString)
        assertThat(completed.path("state").asString()).isEqualTo("COMMITTED")
        assertThat(completed.path("result").path("created").asInt()).withFailMessage(completed.toString()).isEqualTo(2)
        assertThat(completed.path("result").path("failed").asInt()).isEqualTo(1)
        assertThat(request("POST", url, token).status).isEqualTo(200)
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM customer")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM subscription WHERE status='ISOLATED'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM subscriber_access")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM customer_migration")).isEqualTo("2")
        }
    }
}
