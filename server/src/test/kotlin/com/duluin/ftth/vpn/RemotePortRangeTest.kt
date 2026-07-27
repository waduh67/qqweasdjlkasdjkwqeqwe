package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.vpn.domain.model.RemotePortRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Menguji alokasi port remote — murni domain, cermin TunnelSubnet. */
class RemotePortRangeTest {

    @Test
    fun `allocate mengembalikan port terendah yang belum terpakai`() {
        val range = RemotePortRange(20000, 40000)

        assertThat(range.allocate(emptySet())).isEqualTo(20000)
        assertThat(range.allocate(setOf(20000, 20001, 20003))).isEqualTo(20002)
    }

    @Test
    fun `allocate melempar bila rentang habis`() {
        val range = RemotePortRange(20000, 20001)

        assertThatThrownBy { range.allocate(setOf(20000, 20001)) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `konstruktor menolak batas di luar 1-65535`() {
        assertThatThrownBy { RemotePortRange(0, 40000) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { RemotePortRange(20000, 70000) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `konstruktor menolak min lebih besar dari max`() {
        assertThatThrownBy { RemotePortRange(40000, 20000) }.isInstanceOf(ValidationException::class.java)
    }
}
