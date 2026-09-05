package com.duluin.ftth.hris

import com.duluin.ftth.hris.application.port.HrisOutcomePayload
import com.duluin.ftth.hris.adapter.outbound.persistence.HrisOutcomePersistenceAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import com.duluin.ftth.hris.adapter.outbound.persistence.HrisOutcomeJpaRepository
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

class HrisOutcomePersistenceAdapterTest {
    @Test
    fun `repository mapper round trips durable HRIS outcome payload`() {
        val jpa = mock(HrisOutcomeJpaRepository::class.java)
        `when`(jpa.save(any())).thenAnswer { it.arguments[0] }
        val adapter = HrisOutcomePersistenceAdapter(jpa, JsonMapper.builder().findAndAddModules().build())
        val payload = HrisOutcomePayload(
            targetId = UUID.randomUUID(),
            actorId = UUID.randomUUID(),
            resultId = UUID.randomUUID(),
            resultType = "AttendanceFact",
            state = "ACCEPTED",
            fromDate = LocalDate.of(2026, 9, 4),
            toDate = LocalDate.of(2026, 9, 4),
        )

        val restored = adapter.record(UUID.randomUUID(), "attendance", "operation-1", "hash", payload)

        assertThat(restored.payload).isEqualTo(payload)
        assertThat(restored.payloadHash).isEqualTo("hash")
    }
}
