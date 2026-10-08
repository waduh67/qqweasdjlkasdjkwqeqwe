package com.duluin.ftth.inventory

import com.duluin.ftth.platformbilling.application.port.inbound.ConfigureSubscriptionCommand
import com.duluin.ftth.platformbilling.application.port.inbound.ManageTenantSubscriptionUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.ManualPaymentCommand
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.application.service.PlatformBillingRunner
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class ReferenceReadOnlyLockIT : WarehouseMasterHttpFixture() {
    @Autowired private lateinit var subscriptions: TenantSubscriptionRepository
    @Autowired private lateinit var invoices: TenantSubscriptionInvoiceRepository
    @Autowired private lateinit var billingRunner: PlatformBillingRunner
    @Autowired private lateinit var billing: ManageTenantSubscriptionUseCase

    private data class Setup(val owner: String, val technician: String, val tenantId: UUID,
        val warehouse: String, val sku: String, val work: String)

    private fun setup(): Setup {
        val owner = tenant()
        val me = mapper.readTree(request("GET", "/api/me", owner).contentAsString)
        val tenantId = UUID.fromString(me.path("tenantId").asString())
        val role = mapper.readTree(request("GET", "/api/roles", owner).contentAsString)
            .single { it.path("name").asString() == "Teknisi FO" }.path("id").asString()
        val slug = me.path("email").asString().substringAfter('@').substringBefore(".test")
        val email = "field@$slug.test"
        val member = request("POST", "/api/users", owner, mapper.writeValueAsString(mapOf("name" to "Teknisi FO",
            "email" to email, "password" to "secret12345", "roleIds" to listOf(role))))
        assertThat(member.status).isEqualTo(201)
        val technicianId = mapper.readTree(member.contentAsString).path("id").asString()
        assertThat(request("PUT", "/api/users/$technicianId/access", owner,
            mapper.writeValueAsString(mapOf("roleIds" to listOf(role), "areaIds" to listOf(area(owner))))).status).isEqualTo(200)
        val technician = login(slug, email)
        val warehouse = create("locations", owner, """{"code":"LOCK-WH","name":"Gudang","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val sku = create("skus", owner, """{"code":"LOCK-ITEM","name":"Konektor","tracking":"BULK","baseUnit":"EA"}""").path("id").asString()
        assertThat(request("POST", "/api/v2/warehouse/workflow/drain", owner, """{"expectedEpoch":0}""").status).isEqualTo(200)
        val review = mapper.readTree(request("GET", "/api/v2/warehouse/workflow/review", owner).contentAsString)
        assertThat(request("POST", "/api/v2/warehouse/workflow/activate", owner,
            """{"expectedEpoch":1,"reviewHash":"${review.path("reviewHash").asString()}","reason":"Alur baru"}""").status).isEqualTo(200)
        val type = mapper.readTree(request("GET", "/api/v2/work-orders/types", owner).contentAsString).first().path("id").asString()
        val work = request("POST", "/api/v2/work-orders", owner,
            """{"typeId":"$type","title":"Pekerjaan lapangan","technicianId":"$technicianId","areaId":"${area(owner)}"}""")
        assertThat(work.status).withFailMessage(work.contentAsString).isEqualTo(201)
        return Setup(owner, technician, tenantId, warehouse, sku, mapper.readTree(work.contentAsString).path("id").asString())
    }

    private fun lock(setup: Setup) {
        billing.configure(setup.tenantId, ConfigureSubscriptionCommand(BigDecimal("150000"), null, null))
        val subscription = requireNotNull(subscriptions.findByTenantId(setup.tenantId))
        val due = LocalDate.now().minusDays(60)
        invoices.save(TenantSubscriptionInvoice.create(setup.tenantId, subscription.id, "SUB-${UUID.randomUUID()}",
            due, due.plusMonths(1).minusDays(1), BigDecimal("150000"), due))
        billingRunner.enforce(setup.tenantId)
    }

    private fun denied(method: String, path: String, token: String, body: String) {
        val response = request(method, path, token, body)
        assertThat(response.status).withFailMessage("$path: ${response.contentAsString}").isEqualTo(402)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("SUBSCRIPTION_LOCKED")
    }

    @Test fun `overdue reference catalog and receipt writes stop while stock remains readable and payment reopens the same session`() {
        val setup = setup()
        val receipt = """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"${setup.sku}","quantityBase":"3"}]}"""
        lock(setup)

        denied("POST", "/api/v2/warehouse/receipts", setup.owner, receipt)
        denied("POST", "/api/v2/warehouse/skus", setup.owner, """{"code":"BLOCKED","name":"Barang","tracking":"BULK","baseUnit":"EA"}""")

        val stock = request("GET", "/api/v2/warehouse/stock/${setup.sku}", setup.owner)
        assertThat(stock.status).isEqualTo(200)
        assertThat(mapper.readTree(stock.contentAsString).path("positions").isEmpty).isTrue()
        assertThat(request("GET", "/api/v2/warehouse/skus", setup.owner).status).isEqualTo(200)
        assertThat(request("GET", "/api/v2/warehouse/stock/${setup.sku}/history", setup.owner).status).isEqualTo(200)
        val subscription = requireNotNull(subscriptions.findByTenantId(setup.tenantId))
        invoices.findOutstandingBySubscriptionId(subscription.id).forEach { billing.recordManualPayment(it.id, ManualPaymentCommand(null, "Pelunasan")) }
        assertThat(request("POST", "/api/v2/warehouse/receipts", setup.owner, receipt).status).isEqualTo(201)
    }

    @Test fun `overdue own requests and count snapshots cannot write and existing request reads remain available`() {
        val setup = setup()
        val body = """{"kind":"RESTOCK","reason":"Persediaan lapangan","lines":[{"skuId":"${setup.sku}","baseUnit":"EA","requestedBase":"2"}]}"""
        val submitted = request("POST", "/api/v2/warehouse/requests", setup.technician, body)
        assertThat(submitted.status).isEqualTo(201)
        val id = mapper.readTree(submitted.contentAsString).path("id").asString()
        lock(setup)

        denied("POST", "/api/v2/warehouse/requests", setup.technician, body)
        denied("POST", "/api/v2/warehouse/counts/snapshot", setup.owner, """{"locationId":"${setup.warehouse}","skuId":"${setup.sku}"}""")
        denied("PUT", "/api/v2/warehouse/settings", setup.owner, """{"expectedRevision":0,"requireManagerApproval":false,"overdueDays":5}""")

        assertThat(request("GET", "/api/v2/warehouse/requests/$id", setup.technician).status).isEqualTo(200)
        assertThat(request("GET", "/api/v2/warehouse/requests", setup.technician).status).isEqualTo(200)
        assertThat(request("GET", "/api/v2/warehouse/my-materials", setup.technician).status).isEqualTo(200)
    }

    @Test fun `overdue technician progress evidence and completion leave the assignment unchanged and readable`() {
        val setup = setup()
        lock(setup)

        denied("POST", "/api/v2/work-orders/${setup.work}/progress", setup.technician, """{"expectedRevision":0,"state":"BLOCKED","notes":"Menunggu akses"}""")
        denied("POST", "/api/v2/work-orders/${setup.work}/complete", setup.technician, """{"expectedRevision":0,"notes":"Selesai","materials":[]}""")
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        val upload = mvc.perform(multipart("/api/v2/work-orders/${setup.work}/evidence")
            .file(MockMultipartFile("file", "proof.png", "image/png", png)).param("expectedRevision", "0").param("slot", "Bukti Pasang")
            .header("Authorization", "Bearer ${setup.technician}").header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().response
        assertThat(upload.status).withFailMessage(upload.contentAsString).isEqualTo(402)

        val detail = request("GET", "/api/v2/work-orders/${setup.work}", setup.technician)
        assertThat(detail.status).isEqualTo(200)
        assertThat(mapper.readTree(detail.contentAsString).path("workOrder").path("revision").asLong()).isZero()
        assertThat(request("GET", "/api/v2/work-orders/${setup.work}/evidence", setup.technician).status).isEqualTo(200)
    }
}
