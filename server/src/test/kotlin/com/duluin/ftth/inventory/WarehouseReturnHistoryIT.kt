package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReturnHistoryIT : WarehouseRepairFixture() {
    @Test fun `paged return history includes repair transitions exactly once and rejects unbounded requests`() {
        val setup = repairSetup()
        val outbound = dispatchRepair(setup)
        val inbound = receiveRepair(setup, outbound)
        inspectRepair(setup, inbound)
        val revisions = (0..2).flatMap { page ->
            val response = request("GET", "${setup.path}/history?page=$page&size=2", setup.token)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            val body = mapper.readTree(response.contentAsString)
            assertThat(body.size()).isEqualTo(2)
            body.asSequence().map { it.path("revision").asLong() }.toList()
        }
        assertThat(revisions).containsExactly(0L, 1L, 2L, 3L, 4L, 5L)
        assertThat(request("GET", "${setup.path}/history?size=101", setup.token).status).isEqualTo(400)
        assertThat(request("GET", "${setup.path}/history?page=-1", setup.token).status).isEqualTo(400)
    }
}
