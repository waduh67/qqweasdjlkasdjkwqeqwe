package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ConfigurableApplicationContext
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class WarehousePostingRegressionSupport {
    internal lateinit var database: WarehouseSchemaDatabase
    internal lateinit var context: ConfigurableApplicationContext
    @BeforeAll fun startRegressionContext() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stopRegressionContext() { context.close(); database.close() }
    internal fun fixture() = WarehousePostingFixture(context).also { it.setup() }
}

internal fun WarehousePostingFixture.state(identity: UUID): String = scalar("""
    SELECT balance.status || '/' || asset.status FROM inventory_balance_projection balance
    JOIN inventory_serialized_asset asset ON asset.tenant_id=balance.tenant_id AND asset.id=balance.stock_identity_id
    WHERE balance.stock_identity_id='$identity' AND balance.quantity_base>0
""")

internal fun WarehousePostingFixture.reclassify(piece: PostingDimension, quantity: StockQuantity, from: InventoryStatus, to: InventoryStatus, reverse: Boolean = false): WarehousePost {
    val command=move(piece,piece,quantity)
    val legs=command.legs.map { it.copy(status=if(it.direction==LegDirection.IN) to else from) }
    return command.copy(legs=if(reverse) legs.reversed() else legs)
}

internal fun WarehousePostingFixture.fact(piece: PostingDimension, quantity: StockQuantity = StockQuantity.each("1")) =
    PostingMaterialFact(UUID.randomUUID(),piece.stockIdentityId,customer,workOrder,"Regression",quantity,1,true,false)
