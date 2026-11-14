package com.karuhun.ftth.fieldservice

import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.fieldservice.domain.Ipv4Block
import com.karuhun.ftth.fieldservice.adapter.outbound.network.ProcessIpPingAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class Ipv4BlockTest {
    @Test fun slash23ListsBothOctetsAndCanonicalizesHostInput() {
        val block=Ipv4Block.parse("157.15.123.81/23")
        assertThat(block.cidr).isEqualTo("157.15.122.0/23")
        assertThat(block.subnet).isEqualTo("255.255.254.0")
        assertThat(block.size).isEqualTo(512)
        assertThat(block.hosts).isEqualTo(510)
        assertThat((block.start..block.end).map(Ipv4Block::address)).hasSize(512).contains("157.15.123.0")
        assertThat(block.role(block.start)).isEqualTo("NETWORK")
        assertThat(block.role(block.end)).isEqualTo("BROADCAST")
    }
    @Test fun pointToPointAndSingleHostDoNotReserveAddresses() {
        val two=Ipv4Block.parse("203.0.113.255/31")
        assertThat(two.cidr).isEqualTo("203.0.113.254/31")
        assertThat(two.hosts).isEqualTo(2)
        assertThat(two.role(two.start)).isEqualTo("HOST")
        assertThat(two.role(two.end)).isEqualTo("HOST")
        val one=Ipv4Block.parse("255.255.255.255/32")
        assertThat(one.start).isEqualTo(4294967295L)
        assertThat(one.size).isEqualTo(1)
        assertThat(one.hosts).isEqualTo(1)
    }
    @Test fun rejectsInvalidAddressesOversizedBlocksAndShellFragments() {
        for(value in listOf("157.15.122.0/19","157.15.122.0/33","::1/32","256.0.0.1/23","1.02.3.4/23","1.2.3/23","1.2.3.4/23/24","1.2.3.4;whoami/23"))
            assertThatThrownBy { Ipv4Block.parse(value) }.isInstanceOf(ValidationException::class.java)
        assertThat(Ipv4Block.parse("203.0.113.12/20").size).isEqualTo(4096)
    }
    @Test fun realLoopbackPingAndShellFragmentRejection() {
        val adapter=ProcessIpPingAdapter()
        assertThat(adapter.ping("127.0.0.1").status).isEqualTo("UP")
        assertThatThrownBy { adapter.ping("127.0.0.1 & whoami") }.isInstanceOf(ValidationException::class.java)
    }
}
