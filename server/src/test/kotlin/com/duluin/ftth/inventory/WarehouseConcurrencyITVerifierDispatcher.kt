package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.service.WarehouseOutboxDispatcher
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class WarehouseConcurrencyITVerifierDispatcher {
    @Test fun `AV6-03 missing policy for first tenant does not block healthy tenant delivery`(output: CapturedOutput) {
        WarehouseSchemaDatabase().use { database -> postingContext(database).use { context ->
            val bad=WarehousePostingFixture(context,java.util.UUID.randomUUID())
            bad.transaction { sql("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','missing-$tenant','Missing policy')") }
            val good=WarehousePostingFixture(context).also { it.setup() }
            good.transaction { receipt(StockQuantity.each("1")) }
            val tenants=context.getBean(TenantApi::class.java)
            val ordered=object : TenantApi by tenants { override fun findActiveTenantIds() = listOf(bad.tenant,good.tenant) }
            val dispatcher=WarehouseOutboxDispatcher(context.getBean(WarehouseOutboxDeliveryStore::class.java),context.getBean(WarehouseDeliveryReader::class.java),
                context.getBean(WarehouseOutboxDeliveryPort::class.java),ordered)
            assertThatCode { dispatcher.drain() }.doesNotThrowAnyException()
            assertThat(output.all).contains("warehouse_delivery_tenant_failed tenant=${bad.tenant} code=CUTOVER_REQUIRED")
                .doesNotContain("Kebijakan cutover tenant belum diinisialisasi", "WarehouseContractException:")
            bad.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_outbox")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_outbox_delivery WHERE state='DELIVERED'")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_inbox")).isEqualTo("0")
            }
            good.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_outbox_delivery WHERE state='DELIVERED'")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_inbox")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM fulfillment_warehouse_observation")).isEqualTo("1")
            }
        } }
    }
}
