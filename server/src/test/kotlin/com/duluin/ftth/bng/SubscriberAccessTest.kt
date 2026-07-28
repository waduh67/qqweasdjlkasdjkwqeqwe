package com.duluin.ftth.bng

import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Menguji transisi bendera throttle FUP pada akun jaringan — murni domain. */
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
}
