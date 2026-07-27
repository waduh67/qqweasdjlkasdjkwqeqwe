package com.duluin.ftth.vpn

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.vpn.domain.model.VpnNodeToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Menguji penerbitan & hashing token node — murni domain. */
class VpnNodeTokenTest {

    @Test
    fun `issue menghasilkan token berawalan dan menyimpan hanya hash + hint`() {
        val serverId = UuidV7.generate()

        val (token, raw) = VpnNodeToken.issue(serverId)

        assertThat(raw).startsWith(VpnNodeToken.PREFIX)
        assertThat(token.serverId).isEqualTo(serverId)
        // Yang tersimpan hash, bukan token mentah.
        assertThat(token.tokenHash).isEqualTo(VpnNodeToken.hash(raw))
        assertThat(token.tokenHash).isNotEqualTo(raw)
        assertThat(raw).endsWith(token.tokenHint)
    }

    @Test
    fun `hash deterministik dan sepanjang SHA-256 hex`() {
        val raw = VpnNodeToken.generate()

        assertThat(VpnNodeToken.hash(raw)).isEqualTo(VpnNodeToken.hash(raw))
        assertThat(VpnNodeToken.hash(raw)).hasSize(64).matches("[0-9a-f]{64}")
    }

    @Test
    fun `setiap penerbitan unik`() {
        val serverId = UuidV7.generate()

        val (_, first) = VpnNodeToken.issue(serverId)
        val (_, second) = VpnNodeToken.issue(serverId)

        assertThat(first).isNotEqualTo(second)
    }
}
