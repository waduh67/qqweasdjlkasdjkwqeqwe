package com.duluin.ftth.onboarding

import com.duluin.ftth.onboarding.application.service.CustomerCsvParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class CustomerCsvBatchTest {
    @Test fun `password whitespace is preserved while blank passwords stay absent`() {
        for (column in listOf("password", "mikrotik_password")) {
            val csv = "username,$column\nani,\" pass word \"\nbudi,   \n"
            val parsed = CustomerCsvParser.parse(csv.byteInputStream(), csv.toByteArray().size.toLong())
            assertThat(parsed.errors).isEmpty()
            assertThat(parsed.rows[0].mikrotikPassword).isEqualTo(" pass word ")
            assertThat(parsed.rows[1].mikrotikPassword).isNull()
        }
    }
    @Test fun `generic credential columns and legacy aliases preserve identical values`() {
        fun parse(header: String) = "$header\npelanggan0001,legacy-pass\n".let {
            CustomerCsvParser.parse(it.byteInputStream(), it.toByteArray().size.toLong())
        }
        val generic = parse("username,password")
        val legacy = parse("mikrotik_username,mikrotik_password")
        assertThat(generic.errors).isEmpty()
        assertThat(legacy.errors).isEmpty()
        assertThat(generic.rows).isEqualTo(legacy.rows)
        assertThat(generic.rows.single().mikrotikUsername).isEqualTo("pelanggan0001")
        assertThat(generic.rows.single().mikrotikPassword).isEqualTo("legacy-pass")
        val ambiguous = parse("username,mikrotik_username")
        assertThat(ambiguous.errors).anyMatch { it.column == "username" && it.code == "DUPLICATE_COLUMN" }
    }
    @Test fun `csv accepts optional normalized ONU serial and rejects duplicate device assignments`() {
        val csv = "customer_code,onu_serial\nA, onu-123 \nB,ONU-123\nC,\n"
        val parsed = CustomerCsvParser.parse(csv.byteInputStream(), csv.toByteArray().size.toLong())
        assertThat(parsed.rows[0].onuSerial).isEqualTo("ONU-123")
        assertThat(parsed.rows[2].onuSerial).isNull()
        assertThat(parsed.errors).hasSize(1)
        assertThat(parsed.errors.single().column).isEqualTo("onu_serial")
        assertThat(parsed.errors.single().row).isEqualTo(3)
    }
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
