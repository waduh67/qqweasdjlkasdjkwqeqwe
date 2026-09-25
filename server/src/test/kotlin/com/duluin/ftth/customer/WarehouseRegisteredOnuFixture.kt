package com.duluin.ftth.customer

import com.duluin.ftth.inventory.MaterialReceiptRequest
import com.duluin.ftth.inventory.MaterialReceiptSelection
import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseIssueFixture
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/** Current network fixtures obtain each ONU through real warehouse and field commands. */
abstract class WarehouseRegisteredOnuFixture : WarehouseIssueFixture() {
    private data class RegistrationStock(val stock: Setup, val technician: Pair<String, String>)
    private val registrationStocks = mutableMapOf<String, RegistrationStock>()
    private var registrationTenant: String? = null

    override fun tenant(slug: String): String = registrationTenant ?: super.tenant(slug)

    private fun registrationStock(token: String): RegistrationStock = registrationStocks.getOrPut(token) {
        registrationTenant = token
        val stock = try { setupReceipt() } finally { registrationTenant = null }
        val configured = request("PUT", "/api/v1/warehouse/skus/${stock.onu}", token,
            """{"expectedRevision":0,"code":"ONU","name":"ONU","category":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false,"allowedOwnershipModes":["LOAN","SALE"]}""")
        assertThat(configured.status).withFailMessage(configured.contentAsString).isEqualTo(200)
        val technician = technician(token, setOf("customer.onu.assign"))
        create("locations", token, """{"code":"CUSTOMER_INSTALLED","name":"Installed customer equipment","kind":"CUSTOMER_SITE"}""")
        val transit = create("locations", token, """{"code":"WO_TRANSIT","name":"Transit","kind":"TRANSIT"}""").path("id").asString()
        val field = create("locations", token,
            """{"code":"FIELD_STOCK","name":"Field custody","kind":"TECHNICIAN","custodianId":"${technician.second}"}""").path("id").asString()
        for (location in listOf(stock.bin, transit, field)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${technician.second}/$location", token,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        RegistrationStock(stock, technician)
    }

    protected fun registerWarehouseOnu(token: String, customer: String, serial: String): String {
        val prepared = registrationStock(token)
        val stock = prepared.stock
        val receipt = draft(stock, mapper.writeValueAsString(mapOf("skuId" to stock.onu, "quantityBase" to "1",
            "serials" to listOf(mapOf("serial" to serial))))).path("id").asString()
        transition(stock, receipt, "receive", """{"expectedRevision":0}""")
        val received = request("GET", "/api/v1/warehouse/receipts/$receipt", token)
        assertThat(received.status).isEqualTo(200)
        val receiptLine = mapper.readTree(received.contentAsString).path("lines").single()
        val identity = receiptLine.path("pieces").single().path("stockIdentityId").asString()
        transition(stock, receipt, "putaway", mapper.writeValueAsString(mapOf("expectedRevision" to 1,
            "destinationLocationId" to stock.bin, "lines" to listOf(mapOf("lineId" to receiptLine.path("id").asString(),
                "stockIdentityId" to identity, "quantityBase" to "1", "baseUnit" to "EA")))))

        val work = workOrder(token, "PSB", customer)
        assign(token, work, prepared.technician.second)
        putPlan(token, work, plan(token, work, "[${line(stock.onu, "1", "EA")}]"))
        action(token, work, "submit-request", command(token, work, 1))
        action(token, work, "reserve", command(token, work, 1))
        val setup = IssueSetup(stock, work, prepared.technician.second)
        val picked = action(token, work, "pick", pickBody(setup))
        val issued = action(token, work, "dispatch", transitionBody(setup, picked))
        val issuedLine = issued.path("lines").single()
        val issueLine = UUID.fromString(issuedLine.path("id").asString())
        val selected = MaterialReceiptSelection(issueLine, UUID.fromString(identity), WarehouseBaseUnit.EA,
            "1", serial = serial)
        val receiptInput = MaterialReceiptRequest(UUID.fromString(issued.path("issueId").asString()), issued.path("revision").asLong(),
            issued.path("workOrderRevision").asLong(), "Network regression handover", listOf(selected))
        val acknowledged = request("POST", "/api/work-orders/$work/materials/acknowledge", prepared.technician.first,
            mapper.writeValueAsString(receiptInput))
        assertThat(acknowledged.status).withFailMessage(acknowledged.contentAsString).isEqualTo(200)
        val revision = summary(token, work).path("revisions").path("workOrderRevision").asLong()
        val authorization = request("POST", "/api/work-orders/$work/assets/authorize", prepared.technician.first,
            """{"expectedRevision":$revision,"assetId":"$identity","issueLineId":"$issueLine","purpose":"INSTALL"}""")
        assertThat(authorization.status).withFailMessage(authorization.contentAsString).isEqualTo(200)
        val authorizationId = mapper.readTree(authorization.contentAsString).path("authorizationId").asString()
        val installed = request("POST", "/api/customers/$customer/onus", prepared.technician.first,
            """{"serialNumber":"$serial","deployment":{"authorizationId":"$authorizationId","expectedRevision":0,"topology":null}}""")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        return mapper.readTree(installed.contentAsString).path("id").asString()
    }
}
