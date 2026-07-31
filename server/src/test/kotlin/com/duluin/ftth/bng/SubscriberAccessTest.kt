package com.duluin.ftth.bng

import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Menguji throttle FUP + skema identitas per-tipe akun jaringan — murni domain. */
class SubscriberAccessTest {

    private fun access(status: AccessStatus = AccessStatus.ACTIVE) = SubscriberAccess.create(
        tenantId = UuidV7.generate(),
        subscriptionId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        username = "pppoe01",
        secret = "rahasia123",
        planId = UuidV7.generate(),
        nasId = UuidV7.generate(),
        status = status,
    )

    private fun macAccess(
        username: String,
        authType: AuthType = AuthType.DHCP,
        framedIp: String? = null,
    ) = SubscriberAccess.create(
        tenantId = UuidV7.generate(),
        subscriptionId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        username = username,
        // secret diabaikan untuk tipe berbasis MAC — MAC yang jadi password.
        secret = "",
        planId = UuidV7.generate(),
        nasId = UuidV7.generate(),
        status = AccessStatus.ACTIVE,
        authType = authType,
        framedIp = framedIp,
    )

    @Test
    fun `akun baru belum ter-throttle FUP`() {
        assertThat(access().fupThrottled).isFalse()
    }

    @Test
    fun `applyFupThrottle menandai, clearFupThrottle mencabut`() {
        val a = access()

        a.applyFupThrottle()
        assertThat(a.fupThrottled).isTrue()

        a.clearFupThrottle()
        assertThat(a.fupThrottled).isFalse()
    }

    @Test
    fun `clearFupThrottle idempoten pada akun yang belum ter-throttle`() {
        val a = access()
        a.clearFupThrottle()
        assertThat(a.fupThrottled).isFalse()
    }

    @Test
    fun `applyFupThrottle ditolak pada akun terhenti`() {
        val a = access()
        a.terminate()
        assertThatThrownBy { a.applyFupThrottle() }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `clearFupThrottle tetap boleh pada akun terhenti — pemulihan aman kapan pun`() {
        val a = access()
        a.applyFupThrottle()
        a.terminate()

        a.clearFupThrottle()
        assertThat(a.fupThrottled).isFalse()
    }

    // ---- Skema identitas per-tipe ----

    @Test
    fun `DHCP menormalkan MAC ke bentuk kanonik dan menjadikannya password`() {
        // Terima huruf kecil + pemisah tanda hubung → dikanonkan ke AA:BB:...
        val a = macAccess(username = "aa-bb-cc-dd-ee-ff")
        assertThat(a.username).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(a.secret).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(a.framedIp).isNull()
    }

    @Test
    fun `MAC tak valid ditolak`() {
        assertThatThrownBy { macAccess(username = "bukan-mac") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `STATIC wajib punya reservasi IP`() {
        assertThatThrownBy { macAccess(username = "AABBCCDDEEFF", authType = AuthType.STATIC) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `STATIC dengan IP valid menyimpan reservasi Framed-IP-Address`() {
        val a = macAccess(username = "AABBCCDDEEFF", authType = AuthType.STATIC, framedIp = "100.64.0.10")
        assertThat(a.username).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(a.framedIp).isEqualTo("100.64.0.10")
    }

    @Test
    fun `STATIC menolak IP yang bukan IPv4`() {
        assertThatThrownBy {
            macAccess(username = "AABBCCDDEEFF", authType = AuthType.STATIC, framedIp = "999.1.1.1")
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `resetSecret ditolak pada akun berbasis MAC`() {
        val a = macAccess(username = "AABBCCDDEEFF")
        assertThatThrownBy { a.resetSecret("passwordbaru") }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `DHCP boleh tanpa reservasi IP (dinamis)`() {
        val a = macAccess(username = "AABBCCDDEEFF", authType = AuthType.DHCP)
        assertThat(a.framedIp).isNull()
    }
}
