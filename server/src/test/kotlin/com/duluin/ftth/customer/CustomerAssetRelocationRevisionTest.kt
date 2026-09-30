package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

class CustomerAssetRelocationRevisionTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `legacy relocation replay keeps its original JSON shape`() {
        val original = mapper.writeValueAsString(mapOf("operationId" to UUID.randomUUID(), "onuId" to UUID.randomUUID(), "revision" to 4,
            "topology" to mapOf("odpId" to UUID.randomUUID(), "portNumber" to 2, "installRxPowerDbm" to null)))

        val replay = mapper.writeValueAsString(mapper.readValue(original, CustomerAssetRelocation::class.java))

        assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(original))
    }

    @Test
    fun `current relocation serializes its separate authoritative episode revision`() {
        val relocation = CustomerAssetRelocation(UUID.randomUUID(), UUID.randomUUID(), 4,
            CustomerAssetTopology(UUID.randomUUID(), 2, null), 1)

        val response = mapper.readTree(mapper.writeValueAsString(relocation))

        assertThat(response.path("revision").asLong()).isEqualTo(4)
        assertThat(response.path("episodeRevision").asLong()).isEqualTo(1)
    }
}
