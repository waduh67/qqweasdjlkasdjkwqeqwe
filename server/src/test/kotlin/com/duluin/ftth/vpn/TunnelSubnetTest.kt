package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.vpn.domain.model.TunnelSubnet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Menguji aritmetika subnet tunnel IPv4 (parse/serverAddress/netmask/allocate/contains) —
 * murni domain, tanpa Spring maupun database.
 */
class TunnelSubnetTest {

    @Test
    fun `parse CIDR valid`() {
        val subnet = TunnelSubnet.parse("10.8.0.0/24")
        assertThat(subnet.prefix).isEqualTo(24)
        assertThat(subnet.cidr).isEqualTo("10.8.0.0/24")
    }

    @Test
    fun `parse menormalisasi alamat host ke network`() {
        assertThat(TunnelSubnet.parse("10.8.0.55/24").networkAddress()).isEqualTo("10.8.0.0")
    }

    @Test
    fun `parse menolak oktet di luar 0-255`() {
        assertThatThrownBy { TunnelSubnet.parse("10.8.0.256/24") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `parse menolak prefix di luar 8-30`() {
        assertThatThrownBy { TunnelSubnet.parse("10.8.0.0/31") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { TunnelSubnet.parse("10.8.0.0/7") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `parse menolak yang bukan IPv4 dotted-quad`() {
        assertThatThrownBy { TunnelSubnet.parse("bukan-ip/24") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { TunnelSubnet.parse("10.8.0/24") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { TunnelSubnet.parse("10.8.0.0") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `serverAddress netmask networkAddress untuk slash 24`() {
        val subnet = TunnelSubnet.parse("10.8.0.0/24")
        assertThat(subnet.networkAddress()).isEqualTo("10.8.0.0")
        assertThat(subnet.serverAddress()).isEqualTo("10.8.0.1")
        assertThat(subnet.netmask()).isEqualTo("255.255.255.0")
    }

    @Test
    fun `serverAddress netmask networkAddress untuk slash 30`() {
        val subnet = TunnelSubnet.parse("10.0.0.0/30")
        assertThat(subnet.networkAddress()).isEqualTo("10.0.0.0")
        assertThat(subnet.serverAddress()).isEqualTo("10.0.0.1")
        assertThat(subnet.netmask()).isEqualTo("255.255.255.252")
    }

    @Test
    fun `allocate memberi host terendah lalu melewati yang terpakai`() {
        val subnet = TunnelSubnet.parse("10.8.0.0/24")
        assertThat(subnet.allocate(emptySet())).isEqualTo("10.8.0.2")
        assertThat(subnet.allocate(setOf("10.8.0.2"))).isEqualTo("10.8.0.3")
        assertThat(subnet.allocate(setOf("10.8.0.2", "10.8.0.3"))).isEqualTo("10.8.0.4")
    }

    @Test
    fun `allocate melempar saat blok habis`() {
        // /30: hanya .2 yang bisa dipakai (.0 network, .1 hub, .3 broadcast).
        val subnet = TunnelSubnet.parse("10.0.0.0/30")
        assertThat(subnet.allocate(emptySet())).isEqualTo("10.0.0.2")
        assertThatThrownBy { subnet.allocate(setOf("10.0.0.2")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `contains menentukan keanggotaan subnet`() {
        val subnet = TunnelSubnet.parse("10.8.0.0/24")
        assertThat(subnet.contains("10.8.0.5")).isTrue()
        assertThat(subnet.contains("10.8.0.255")).isTrue()
        assertThat(subnet.contains("10.9.0.1")).isFalse()
    }
}
