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
import java.util.UUID

class WarehouseDiscoveryITR2ReceiptTiming : CustomerDeploymentFixture() {
    @MockitoSpyBean private lateinit var receipts: DiscoveryReceiptStore

    @ParameterizedTest
    @ValueSource(strings = ["VALID", "INVALID", "EMPTY", "FOREIGN", "RESTORED", "SECOND_INSERT"])
    fun `receipt final validation survives selective timing scope restoration and later mutation`(mode: String) {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val stock = fixture(installation.receipt.stock.token)
        val serial = requireNotNull(installation.receipt.input.lines.single().serial)
        stock.transaction { context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
            listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.now()))) }
        val original = stock.transaction { context.getBean(ManageDiscoveredOnuUseCase::class.java).list(DiscoveredOnuState.DISCOVERED).single() }
        val view = original.copy(state = DiscoveredOnuState.PROVISIONED, suggestion = null)
        val command = ProvisionDiscoveredOnuCommand(installation.customer, null, null, null, installation.authorization, 0, "r2-timing")
        val entityManager = context.getBean(EntityManager::class.java)
        val writer = DiscoveryReceiptStore(entityManager)
        Mockito.doAnswer {
            entityManager.flush()
            stock.transaction {
                sql("SET CONSTRAINTS ALL IMMEDIATE")
                sql("SET CONSTRAINTS monitoring_discovery_receipt_final DEFERRED")
                writer.append(view, if (mode == "INVALID") command.copy(installRxPowerDbm = -19.0) else command)
                if (mode == "SECOND_INSERT") {
                    sql("SET CONSTRAINTS monitoring_discovery_receipt_final IMMEDIATE")
                    sql("SET CONSTRAINTS monitoring_discovery_receipt_final DEFERRED")
                    val second = UUID.randomUUID()
                    sql("""INSERT INTO discovered_onu(id,tenant_id,serial_number,olt_code,last_status,first_seen_at,last_seen_at,seen_count,state)
                        SELECT '$second',tenant_id,serial_number,olt_code,last_status,first_seen_at,last_seen_at,seen_count,state FROM discovered_onu WHERE id='${original.id}'""")
                    sql("""INSERT INTO monitoring_discovery_receipt(tenant_id,discovery_id,authorization_id,operation_key,request_hash,response,request_payload)
                        SELECT tenant_id,'$second',authorization_id,operation_key,request_hash,jsonb_set(response,'{id}',to_jsonb('$second'::text)),request_payload
                        FROM monitoring_discovery_receipt WHERE discovery_id='${original.id}'""")
                }
                if (mode == "EMPTY" || mode == "RESTORED") sql("SET LOCAL app.tenant_id=''")
                if (mode == "FOREIGN") sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                if (mode == "RESTORED") sql("SET LOCAL app.tenant_id='$tenant'")
            }
            Unit
        }.`when`(receipts).append(view, command)
        val body = """{"customerId":"${installation.customer}","authorizationId":"${installation.authorization}","expectedRevision":0,"odpId":null,"portNumber":null,"installRxPowerDbm":null}"""
        try {
            val response = request("POST", "/api/monitoring/discovered-onus/${original.id}/provision", installation.receipt.receiver.first, body, "r2-timing")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(if (mode in setOf("VALID", "RESTORED")) 200 else 409)
            if (mode !in setOf("VALID", "RESTORED")) assertUninstalled(installation)
        } finally { Mockito.reset(receipts) }
        assertThat(request("POST", "/api/monitoring/discovered-onus/${original.id}/provision", installation.receipt.receiver.first, body, "r2-timing").status).isEqualTo(200)
    }
}
