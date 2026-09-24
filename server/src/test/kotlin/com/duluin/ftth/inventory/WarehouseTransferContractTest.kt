package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

class WarehouseTransferContractTest {
    @Test fun `omitting optional position preserves legacy transfer payload hashes`() {
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val legacy = """{"stockIdentityId":"$id","quantityBase":"10","baseUnit":"EA"}"""
        val mapper = jacksonObjectMapper()
        val selection = mapper.readValue(legacy, WarehouseTransferSelection::class.java)
        assertThat(selection.sourceBalanceId).isNull()
        assertThat(WarehouseCanonicalPayload.parse(mapper.writeValueAsString(selection)).hash)
            .isEqualTo(WarehouseCanonicalPayload.parse(legacy).hash)
    }
}
