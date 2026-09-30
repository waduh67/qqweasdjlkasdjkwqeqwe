package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.ApplicationEventMulticaster
import java.util.UUID

class WarehousePostingITDelivery : WarehousePostingRegressionSupport() {
    @Test fun `AV5-02 rollback cannot leak an intermediate application event to an external commit`() {
        val fixture=fixture()
        val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
        val before=fixture.transaction { counts() }
        val marker=UUID.randomUUID()
        val observed=mutableListOf<String>()
        val listener=ApplicationListener<PayloadApplicationEvent<*>> { event ->
            if(event.payload.javaClass.simpleName=="PostingPhaseReached" && event.payload.toString().contains("LEGS")) {
                observed+=fixture.scalar("SELECT state||':'||(SELECT count(*) FROM inventory_movement_leg WHERE movement_id=movement.id) FROM inventory_movement movement ORDER BY server_received_at DESC LIMIT 1")
                database.dataSource.connection.use { connection -> connection.createStatement().use {
                    it.execute("SET app.tenant_id='${fixture.tenant}'")
                    it.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$marker','${fixture.tenant}','$marker','BIN')")
                } }
                error("injected external listener")
            }
        }
        context.addApplicationListener(listener)
        try {
            PostingJdbcProbe(context,TestPostingPhase.EVENTS) { error("injected test-only rollback") }.use {
                assertThatThrownBy { fixture.transaction {
                    post(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1")))
                } }.hasMessageContaining("injected")
            }
            fixture.transaction {
                assertThat(counts()).isEqualTo(before)
                assertThat(scalar("SELECT count(*) FROM inventory_location WHERE id='$marker'")).isEqualTo("0")
            }
            assertThat(observed).isEmpty()
        } finally { context.getBean("applicationEventMulticaster",ApplicationEventMulticaster::class.java).removeApplicationListener(listener) }
    }

    @Test fun `AV5-02 production phase event is not loadable`() {
        assertThatThrownBy { Class.forName("com.duluin.ftth.inventory.application.port.outbound.PostingPhaseReached") }
            .isInstanceOf(ClassNotFoundException::class.java)
    }
}
