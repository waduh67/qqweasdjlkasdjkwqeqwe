package com.duluin.ftth.customer

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.WarehouseMasterHttpFixture
import com.duluin.ftth.onboarding.application.service.CustomerImportPromotionPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import tools.jackson.databind.JsonNode

class WarehouseCompatibilityITCsv : WarehouseMasterHttpFixture() {
    @Test
    fun `tenant scoped promotion creates a customer without an interactive principal`() {
        val token = tenant()
        assertThat(request("POST", "/api/catalog/plans", token,
            """{"name":"Background CSV","price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""").status).isEqualTo(201)
        val csv = "name,address,package_name,mikrotik_username\nBackground customer,Test,Background CSV,background-user\n".toByteArray()
        val row = com.duluin.ftth.onboarding.application.service.CustomerCsvParser.parse(csv.inputStream(), csv.size.toLong()).rows.single()
        val command = com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersCommand(listOf(row))
        TenantContext.runAs(fixture(token).tenant) {
            val result = context.getBean(com.duluin.ftth.onboarding.application.service.CustomerRowImporter::class.java)
                .importRow(command, "background-user", "PPPOE", row)
            assertThat(result.name).isEqualTo("CREATED")
        }
    }

    @Test
    fun `CSV stages commits and replays without physical assignments`() {
        val token = tenant()
        assertThat(request("POST", "/api/catalog/plans", token,
            """{"name":"CSV","price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""").status).isEqualTo(201)
        val staged = stage(token, "name,address,package_name,mikrotik_username\nCSV customer,Test,CSV,csv-user\n")
        val id = staged.path("id").asString()
        val hash = staged.path("sha256").asString()
        val stale = request("POST", "/api/onboarding/v1/import/customers/$id/commit?commitOperationKey=commit&commitHash=${"0".repeat(64)}", token)
        assertThat(stale.status).isEqualTo(409)
        val url = "/api/onboarding/v1/import/customers/$id/commit?commitOperationKey=commit&commitHash=$hash"
        val accepted = request("POST", url, token)
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        val stock = fixture(token)
        TenantContext.runAs(stock.tenant) { context.getBean(CustomerImportPromotionPort::class.java).promoteOne() }
        val completed = request("GET", "/api/onboarding/v1/import/customers/$id", token)
        assertThat(completed.status).isEqualTo(200)
        val result = mapper.readTree(completed.contentAsString)
        assertThat(result.path("state").asString()).isEqualTo("COMMITTED")
        assertThat(result.path("result").path("created").asInt()).isEqualTo(1)
        assertThat(result.path("result").path("failed").asInt()).isZero()
        assertThat(request("POST", url, token).status).isEqualTo(200)
        TenantContext.runAs(stock.tenant) { context.getBean(CustomerImportPromotionPort::class.java).promoteOne() }
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM customer")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM migration_fulfillment_inbox")).isEqualTo("1")
            for (table in listOf("onu", "inventory_serialized_asset", "inventory_asset_assignment", "inventory_movement")) {
                assertThat(scalar("SELECT count(*) FROM $table")).describedAs(table).isEqualTo("0")
            }
        }
    }

    @Test
    fun `malformed CSV records validation failure without misleading physical success`() {
        val token = tenant()
        val staged = stage(token, "name,mikrotik_username\n\"unterminated,user")
        assertThat(staged.path("errors").single().path("code").asString()).isEqualTo("MALFORMED_CSV")
        val response = request("POST", "/api/onboarding/v1/import/customers/${staged.path("id").asString()}/commit?commitOperationKey=commit&commitHash=${staged.path("sha256").asString()}", token)
        assertThat(response.status).isEqualTo(200)
        assertThat(mapper.readTree(response.contentAsString).path("state").asString()).isEqualTo("PERMANENT_FAILED")
        fixture(token).transaction { assertThat(scalar("SELECT count(*) FROM customer")).isEqualTo("0") }
    }

    private fun stage(token: String, csv: String): JsonNode {
        val response = mvc.perform(multipart("/api/onboarding/v1/import/customers")
            .file(MockMultipartFile("file", "customers.csv", "text/csv", csv.toByteArray()))
            .param("operationKey", "csv-stage").header("Authorization", "Bearer $token")).andReturn().response
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(202)
        return mapper.readTree(response.contentAsString)
    }
}
