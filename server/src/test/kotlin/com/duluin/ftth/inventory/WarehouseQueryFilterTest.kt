package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WarehouseQueryFilterTest {
    @Test fun `query defaults and exact serial normalize without unbounded pages`() {
        val filter = WarehouseQueryFilter.parse(mapOf("serial" to listOf(" onu-1 ")))
        assertThat(filter.size).isEqualTo(25)
        assertThat(filter.serial).isEqualTo("ONU-1")
        assertThat(WarehouseQueryFilter.parse(emptyMap(), true).sort).isEqualTo("createdAt")
    }

    @Test fun `unknown repeated malformed and unbounded query values fail closed`() {
        val bad = listOf(mapOf("page" to listOf("0", "1")), mapOf("size" to listOf("101")), mapOf("cost" to listOf("true")),
            mapOf("page" to listOf("-1")), mapOf("from" to listOf("2026-01-01T00:00:00Z")),
            mapOf("from" to listOf("2000-01-01T00:00:00Z"), "until" to listOf("2026-01-01T00:00:00Z")))
        bad.forEach { values -> assertThatThrownBy { WarehouseQueryFilter.parse(values) }.isInstanceOf(WarehouseContractException::class.java) }
    }
}
