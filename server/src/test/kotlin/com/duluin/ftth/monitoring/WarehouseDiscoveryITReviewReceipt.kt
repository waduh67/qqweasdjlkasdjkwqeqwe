package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.time.Instant

class WarehouseDiscoveryITReviewReceipt : CustomerDeploymentFixture() {
    @Test
    fun `DB-2 unconsumed authorization cannot certify an unresolved observation receipt`() {
        val installation = installation()
        val stock = fixture(installation.receipt.stock.token)
        stock.transaction { context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
            listOf(OnuReading("UNRELATED-OBSERVATION", "OLT-X", null, OnuOperationalStatus.ONLINE, null, null, null, null, Instant.now()))) }
        val discovery = stock.transaction { scalar("SELECT id FROM discovered_onu") }
        val failure = assertThrows<Exception> { stock.transaction {
            sql("""INSERT INTO monitoring_discovery_receipt(tenant_id,discovery_id,authorization_id,operation_key,request_hash,response)
                VALUES ('$tenant','$discovery','${installation.authorization}','poison','${"0".repeat(64)}','{}')""")
        } }
        assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23514")
        assertUninstalled(installation)
    }
}
