package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.adapter.outbound.persistence.NormalizedStateJsonCodec
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class NormalizedStateJsonCodecTest {
    private val objectMapper = ObjectMapper()
    private val codec = NormalizedStateJsonCodec(objectMapper)

    @Test
    fun `typed normalized state round trips without mutable generic values`() {
        val state = NormalizedDeviceState.of(
            NormalizedField.INTERFACES to NormalizedValue.sequence(
                NormalizedValue.obj(
                    NormalizedField.NAME to NormalizedValue.identifier("ether1"),
                    NormalizedField.VLANS to NormalizedValue.sequence(
                        NormalizedValue.number(110),
                        NormalizedValue.number(120),
                    ),
                ),
            ),
            NormalizedField.CONFIGURED to NormalizedValue.flag(true),
        )

        assertThat(codec.decode(codec.encode(state))).isEqualTo(state)
    }

    @Test
    fun `decoder preserves safe legacy fields but rejects mutable shaped values and unsafe text`() {
        val legacy = codec.decode("{\"legacyFlag\":true}")
        assertThat(legacy.legacyPayload).isEqualTo("{\"legacyFlag\":true}")

        listOf(
            "{\"name\":null}",
            "{\"name\":{\"name\":\"/interface vlan add\"}}",
        ).forEach { json ->
            assertThatThrownBy { codec.decode(json) }
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `state rejects values that do not match the field contract`() {
        listOf(
            NormalizedField.CONFIGURED to NormalizedValue.number(1),
            NormalizedField.VLAN_ID to NormalizedValue.flag(true),
            NormalizedField.PORT to NormalizedValue.sequence(NormalizedValue.identifier("ether1")),
        ).forEach { invalid ->
            assertThatThrownBy { NormalizedDeviceState.of(invalid) }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("NORMALIZED_FIELD_TYPE_INVALID")
        }
    }
}
