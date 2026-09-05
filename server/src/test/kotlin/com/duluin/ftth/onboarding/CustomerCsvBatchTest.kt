package com.duluin.ftth.onboarding

import com.duluin.ftth.onboarding.application.service.CustomerCsvParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class CustomerCsvBatchTest {
    @Test
    fun `parser accepts BOM and reports duplicate business keys without exposing secret`() {
        val csv = "\uFEFFmikrotik_username,name,mikrotik_password\nuser,=formula,secret\nuser,second,secret2\n"
        val parsed = CustomerCsvParser.parse(ByteArrayInputStream(csv.toByteArray()), csv.toByteArray().size.toLong())

        assertThat(parsed.rows).hasSize(2)
        assertThat(parsed.errors).anyMatch { it.code == "DUPLICATE_KEY" }
        assertThat(parsed.errors.joinToString()).doesNotContain("secret")
    }

    @Test
    fun `parser rejects malformed quoted record`() {
        val csv = "mikrotik_username,name\nuser,\"unterminated\n"
        val parsed = CustomerCsvParser.parse(ByteArrayInputStream(csv.toByteArray()), csv.toByteArray().size.toLong())

        assertThat(parsed.rows).isEmpty()
        assertThat(parsed.errors.single().code).isEqualTo("MALFORMED_CSV")
    }

    @Test
    fun `parser rejects oversized input before reading it`() {
        val bytes = ByteArray((CustomerCsvParser.MAX_BYTES + 1).toInt())

        org.assertj.core.api.Assertions.assertThatThrownBy {
            CustomerCsvParser.parse(ByteArrayInputStream(bytes), bytes.size.toLong())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `parser normalizes zero coordinates to unlocated input`() {
        val csv = "mikrotik_username,latitude,longitude\nuser,0,0\n"
        val parsed = CustomerCsvParser.parse(ByteArrayInputStream(csv.toByteArray()), csv.toByteArray().size.toLong())

        assertThat(parsed.rows.single().latitude).isNull()
        assertThat(parsed.rows.single().longitude).isNull()
    }
}
