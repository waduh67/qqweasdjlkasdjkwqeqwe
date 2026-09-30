package com.duluin.ftth.customer

import com.duluin.ftth.inventory.MaterialMode
import com.duluin.ftth.inventory.MaterialReceiptRequest
import com.duluin.ftth.inventory.MaterialReceiptSelection
import com.duluin.ftth.inventory.MaterialUsageRequest
import com.duluin.ftth.inventory.MaterialUsageSelection
import com.duluin.ftth.inventory.WarehouseBaseUnit
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import java.util.UUID

abstract class WarehouseCompatibilityMaterialFixture : CustomerDeploymentFixture() {
    protected fun mixedMaterials(): Installation {
        val installation = installation()
        val response = consume(installation)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        val case = installation.receipt
        val stock = case.stock
        receiveStock(stock, "1000000")
        create("locations", stock.token, """{"code":"CONSUMED","name":"Consumed material","kind":"TRANSIT"}""")
        val work = workOrder(stock.token, "REPAIR", installation.customer.toString())
        assign(stock.token, work, case.receiver.second)
        putPlan(stock.token, work, plan(stock.token, work, "[${line(stock.cable)}]"))
        action(stock.token, work, "submit-request", command(stock.token, work, 1))
        action(stock.token, work, "reserve", command(stock.token, work, 1))
        val setup = IssueSetup(stock, work, case.receiver.second)
        val picked = action(stock.token, work, "pick", pickBody(setup))
        val issue = action(stock.token, work, "dispatch", transitionBody(setup, picked))
        val issuedLine = issue.path("lines").single()
        val issueLine = UUID.fromString(issuedLine.path("id").asString())
        val receipt = MaterialReceiptRequest(UUID.fromString(issue.path("issueId").asString()), issue.path("revision").asLong(),
            issue.path("workOrderRevision").asLong(), "Compatibility handover", listOf(MaterialReceiptSelection(
                issueLine, UUID.fromString(issuedLine.path("dimension").path("stockIdentityId").asString()), WarehouseBaseUnit.MM, "100000")))
        val received = request("POST", "/api/work-orders/$work/materials/acknowledge", case.receiver.first, mapper.writeValueAsString(receipt))
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(200)
        val acknowledged = mapper.readTree(received.contentAsString)
        val usage = MaterialUsageRequest(0, 1, receipt.workOrderRevision, MaterialMode.MATERIAL_REQUIRED, "Measured 82.500m",
            listOf(MaterialUsageSelection(UUID.fromString(acknowledged.path("receiptId").asString()), issueLine,
                UUID.fromString(acknowledged.path("lines").single().path("accepted").path("stockIdentityId").asString()), "82500", WarehouseBaseUnit.MM)))
        val used = request("POST", "/api/work-orders/$work/materials/report-use", case.receiver.first, mapper.writeValueAsString(usage))
        assertThat(used.status).withFailMessage(used.contentAsString).isEqualTo(200)
        stageLegacyFact(installation)
        return installation
    }

    private fun stageLegacyFact(installation: Installation) {
        val tenant = fixture(installation.receipt.stock.token).tenant
        context.getBean(Flyway::class.java).configuration.dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)").use { statement ->
                statement.setString(1, tenant.toString())
                statement.execute()
            }
            connection.prepareStatement("""INSERT INTO inventory_customer_material_fact
                (id,tenant_id,customer_id,work_order_id,item_category,quantity,installed,returned,recorded_at,operation_key,payload_hash,warehouse_admission)
                VALUES (?,?,?,?,'LEGACY_COUNT',37,true,false,'2020-01-01T00:00:00Z',?,'legacy-snapshot','LEGACY_UNRESOLVED')""").use { statement ->
                val id = UUID.randomUUID()
                statement.setObject(1, id)
                statement.setObject(2, tenant)
                statement.setObject(3, installation.customer)
                statement.setObject(4, UUID.fromString(installation.receipt.workOrder))
                statement.setString(5, "legacy-$id")
                statement.executeUpdate()
            }
            connection.commit()
        }
    }
}
