package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.monitoring.adapter.outbound.persistence.DiscoveryReceiptStore
import com.duluin.ftth.monitoring.application.port.inbound.ManageDiscoveredOnuUseCase
import com.duluin.ftth.monitoring.application.port.inbound.ProvisionDiscoveredOnuCommand
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant

class WarehouseDiscoveryITReviewReceiptTruth : CustomerDeploymentFixture() {
    @MockitoSpyBean private lateinit var receipts: DiscoveryReceiptStore

    @ParameterizedTest
    @ValueSource(strings = ["CONTEXT_HASH", "OPERATION_KEY", "RESPONSE_SERIAL"])
    fun `completed physical graph cannot certify a forged discovery receipt`(mode: String) {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val stock = fixture(installation.receipt.stock.token)
        val serial = requireNotNull(installation.receipt.input.lines.single().serial)
        stock.transaction { context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
            listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.now()))) }
        val original = stock.transaction { context.getBean(ManageDiscoveredOnuUseCase::class.java).list(DiscoveredOnuState.DISCOVERED).single() }
        val view = original.copy(state = DiscoveredOnuState.PROVISIONED, suggestion = null)
        val command = ProvisionDiscoveredOnuCommand(installation.customer, null, null, null, installation.authorization, 0, "receipt-probe")
        val writer = DiscoveryReceiptStore(context.getBean(EntityManager::class.java))
        Mockito.doAnswer {
            val alteredView = if (mode == "RESPONSE_SERIAL") view.copy(serialNumber = "UNRELATED") else view
            val alteredCommand = when (mode) {
                "CONTEXT_HASH" -> command.copy(installRxPowerDbm = -19.0)
                "OPERATION_KEY" -> command.copy(operationKey = "unrelated-operation")
                else -> command
            }
            writer.append(alteredView, alteredCommand)
            Unit
        }.`when`(receipts).append(view, command)
        val body = """{"customerId":"${installation.customer}","authorizationId":"${installation.authorization}","expectedRevision":0,"odpId":null,"portNumber":null,"installRxPowerDbm":null}"""
        try {
            val rejected = request("POST", "/api/monitoring/discovered-onus/${original.id}/provision", installation.receipt.receiver.first, body, "receipt-probe")
            assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(409)
            assertUninstalled(installation)
            stock.transaction { assertThat(scalar("SELECT count(*) FROM monitoring_discovery_receipt")).isEqualTo("0") }
        } finally { Mockito.reset(receipts) }
        val accepted = request("POST", "/api/monitoring/discovered-onus/${original.id}/provision", installation.receipt.receiver.first, body, "receipt-probe")
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM monitoring_discovery_receipt")).isEqualTo("1")
        }
    }
}
