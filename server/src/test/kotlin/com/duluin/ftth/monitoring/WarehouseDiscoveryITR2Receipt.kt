package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.monitoring.adapter.outbound.persistence.DiscoveryReceiptStore
import com.duluin.ftth.monitoring.application.port.inbound.ManageDiscoveredOnuUseCase
import com.duluin.ftth.monitoring.application.port.inbound.ProvisionDiscoveredOnuCommand
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

class WarehouseDiscoveryITR2Receipt : CustomerDeploymentFixture() {
    @MockitoSpyBean private lateinit var receipts: DiscoveryReceiptStore
    @MockitoSpyBean private lateinit var operations: WarehouseOperationStore

    @Test
    fun `DB-R2-1 jointly forged identity and receipt cannot disagree with consumed canonical hash`() {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val stock = fixture(installation.receipt.stock.token)
        val serial = requireNotNull(installation.receipt.input.lines.single().serial)
        stock.transaction { context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
            listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.now()))) }
        val original = stock.transaction { context.getBean(ManageDiscoveredOnuUseCase::class.java).list(DiscoveredOnuState.DISCOVERED).single() }
        val view = original.copy(state = DiscoveredOnuState.PROVISIONED, suggestion = null)
        val command = ProvisionDiscoveredOnuCommand(installation.customer, null, null, null, installation.authorization, 0, "r2-joint")
        val altered = command.copy(installRxPowerDbm = -19.0)
        val writer = DiscoveryReceiptStore(context.getBean(EntityManager::class.java))
        Mockito.doAnswer { invocation ->
            val body = mapper.readTree(invocation.getArgument<String>(1)) as ObjectNode
            (body.path("observation") as ObjectNode).put("requestPayload", mapper.writeValueAsString(altered))
            stock.transaction { jdbc { connection ->
                connection.prepareStatement("INSERT INTO inventory_command_identity(id,tenant_id,canonical_payload,original_session_id) VALUES (?,?,?,?)").use { query ->
                    query.setObject(1, installation.operation); query.setObject(2, tenant)
                    query.setString(3, mapper.writeValueAsString(body)); query.setString(4, invocation.getArgument<String?>(2)); query.executeUpdate()
                }
            } }
            1
        }.`when`(operations).storeIdentity(Mockito.eq(installation.operation) ?: installation.operation, Mockito.anyString(), Mockito.nullable(String::class.java))
        Mockito.doAnswer { writer.append(view, altered); Unit }.`when`(receipts).append(view, command)
        val body = """{"customerId":"${installation.customer}","authorizationId":"${installation.authorization}","expectedRevision":0,"odpId":null,"portNumber":null,"installRxPowerDbm":null}"""
        try {
            val response = request("POST", "/api/monitoring/discovered-onus/${original.id}/provision", installation.receipt.receiver.first, body, "r2-joint")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
            assertUninstalled(installation)
        } finally { Mockito.reset(operations, receipts) }
        assertThat(request("POST", "/api/monitoring/discovered-onus/${original.id}/provision", installation.receipt.receiver.first, body, "r2-joint").status).isEqualTo(200)
    }
}
