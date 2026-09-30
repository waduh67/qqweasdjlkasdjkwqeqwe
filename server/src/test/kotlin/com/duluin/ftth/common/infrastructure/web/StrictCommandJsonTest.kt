package com.duluin.ftth.common.infrastructure.web

import com.duluin.ftth.common.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.core.JacksonException

class StrictCommandJsonTest {
    data class Command(val name: String?, val revision: Long)

    @Test
    fun `null root is rejected before a typed command can escape`() {
        assertThrows<ValidationException> { StrictCommandJson.decode("null", Command::class.java) }
    }

    @Test
    fun `nullable field inside a non-null command remains valid`() {
        val command = StrictCommandJson.decode("""{"name":null,"revision":0}""", Command::class.java)

        assertThat(command).isEqualTo(Command(null, 0))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "\"text\"", "true", "[]"])
    fun `scalar and array roots remain invalid commands`(body: String) {
        assertThrows<JacksonException> { StrictCommandJson.decode(body, Command::class.java) }
    }
}
