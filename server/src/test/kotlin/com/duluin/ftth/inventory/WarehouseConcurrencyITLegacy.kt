package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.DurableInventoryFulfillmentService
import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class WarehouseConcurrencyITLegacy {
    @Test fun `legacy command ignores supplied hash and replays after loss but denies revocation and actor spoof`() {
        WarehouseSchemaDatabase().use { database -> postingContext(database).use { context ->
            val fixture = WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
            try {
                val piece = fixture.transaction { acknowledge(receipt(StockQuantity.each("1"))) }
                val command = InventoryFulfillmentCommand(fixture.tenant,piece.stockIdentityId,piece.stockIdentityId,piece.skuId,piece.locationId,
                    fixture.customer,fixture.workOrder,1,true,true,fixture.actor,"client.namespace","legacy-replay","untrusted-hash","Consume","ONU")
                val service = context.getBean(DurableInventoryFulfillmentService::class.java)
                val first = fixture.transaction { service.apply(command, false) }
                val before = fixture.transaction { counts() }
                assertThat(fixture.transaction { service.apply(command.copy(payloadHash="different-untrusted-hash"), false) }).isEqualTo(first)
                assertThat(fixture.transaction { counts() }).isEqualTo(before)
                assertThatThrownBy { fixture.transaction { service.apply(command.copy(actorId=UUID.randomUUID()), false) } }.hasMessageContaining("FORBIDDEN")
                assertThatThrownBy { fixture.transaction { service.apply(command.copy(reason="changed"), false) } }.hasMessageContaining("IDEMPOTENCY_CONFLICT")
                fixture.authenticateLegacy(UUID.randomUUID())
                fixture.transaction { context.getBean(com.duluin.ftth.iam.application.service.UserService::class.java).setEnabled(actor, false) }
                fixture.authenticateLegacy()
                assertThatThrownBy { fixture.transaction { service.apply(command, false) } }.isInstanceOf(com.duluin.ftth.common.domain.error.AccessDeniedException::class.java)
                SecurityContextHolder.clearContext()
                assertThatThrownBy { fixture.transaction { service.apply(command, false) } }.isInstanceOf(com.duluin.ftth.common.domain.error.AuthenticationException::class.java)
                assertThat(fixture.transaction { counts() }).isEqualTo(before)
            } finally { SecurityContextHolder.clearContext() }
        } }
    }
}
