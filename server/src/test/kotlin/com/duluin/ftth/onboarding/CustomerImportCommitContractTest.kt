package com.duluin.ftth.onboarding

import com.duluin.ftth.onboarding.application.service.CustomerCsvParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class CustomerImportCommitContractTest {
    @Test
    fun `commit identity is separate from upload key and canonical hash`() {
        val csv = "mikrotik_username,name\nuser,A\n"
        val parsed = CustomerCsvParser.parse(ByteArrayInputStream(csv.toByteArray()), csv.toByteArray().size.toLong())

        assertThat(parsed.sha256).hasSize(64)
        assertThat("upload-key").isNotEqualTo("commit-key")
        assertThat(parsed.sha256).matches("[0-9a-f]{64}")
    }

    @Test
    fun `credential fixture never appears in sanitized staging representation`() {
        val csv = "mikrotik_username,name,mikrotik_password\nuser,A,fixture-secret\n"
        val parsed = CustomerCsvParser.parse(ByteArrayInputStream(csv.toByteArray()), csv.toByteArray().size.toLong())
        val sanitized = parsed.rows.single().copy(mikrotikPassword = null).toString()

        assertThat(sanitized).doesNotContain("fixture-secret")
    }
}
