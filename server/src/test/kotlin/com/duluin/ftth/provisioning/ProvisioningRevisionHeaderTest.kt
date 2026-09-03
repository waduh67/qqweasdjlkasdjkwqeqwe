package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.adapter.inbound.web.parseRevision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProvisioningRevisionHeaderTest {
    @Test
    fun `strong and weak etags parse to the same positive revision`() {
        assertThat(parseRevision("\"3\"")).isEqualTo(3)
        assertThat(parseRevision("W/\"3\"")).isEqualTo(3)
    }

    @Test
    fun `invalid revision header fails with stable code`() {
        assertThatThrownBy { parseRevision("0") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessage("REVISION_REQUIRED")
    }
}
