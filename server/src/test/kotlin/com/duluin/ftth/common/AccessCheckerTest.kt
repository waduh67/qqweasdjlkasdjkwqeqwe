package com.duluin.ftth.common

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.SubscriptionLockedException
import com.duluin.ftth.common.infrastructure.security.AccessChecker
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.ReadOnlyLockGuard
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Gerbang izin sekaligus penegak kunci baca-saja. Yang dijaga di sini adalah pembagian kerja
 * antara keduanya: izin yang kurang tetap menghasilkan `false` (→ 403), sedangkan langganan
 * yang menunggak MELEMPAR (→ 402). Dua keadaan itu menuntut tindakan berbeda dari pengguna,
 * jadi jawabannya tak boleh sama.
 */
class AccessCheckerTest {

    private val permissions = setOf(
        "customer.customer.view",
        "customer.customer.create",
        "billing.subscription.renew",
    )

    @Test
    fun `tanpa penjaga kunci, perilakunya persis seperti sebelum ada kunci`() {
        // Potongan aplikasi tanpa module platformbilling (unit test, konteks parsial) tak boleh
        // ikut terkunci hanya karena penjaganya absen.
        val authz = checker(guard = null)

        assertThat(authz.can("customer.customer.create")).isTrue()
        assertThat(authz.can("customer.customer.view")).isTrue()
        assertThat(authz.can("iam.user.create")).isFalse()
    }

    @Test
    fun `saat terkunci, izin tulis melempar sedangkan izin baca tetap lolos`() {
        val authz = checker(guard = FixedLockGuard(locked = true))

        assertThatThrownBy { authz.can("customer.customer.create") }
            .isInstanceOf(SubscriptionLockedException::class.java)
        assertThat(authz.can("customer.customer.view")).isTrue()
    }

    @Test
    fun `saat terkunci, membayar langganan tetap boleh`() {
        // Tanpa pengecualian ini kuncinya menelan dirinya sendiri: tenant yang menunggak tak
        // bisa membayar tunggakan yang membuka kuncinya.
        val authz = checker(guard = FixedLockGuard(locked = true))

        assertThat(authz.can("billing.subscription.renew")).isTrue()
    }

    @Test
    fun `izin yang tak dimiliki tetap false, bukan melempar`() {
        // Urutannya penting: kekurangan izin diputuskan lebih dulu, jadi pengguna yang memang
        // tak berwenang tetap melihat "akses ditolak", bukan "bayar dulu".
        val authz = checker(guard = FixedLockGuard(locked = true))

        assertThat(authz.can("iam.user.create")).isFalse()
    }

    @Test
    fun `canAny lolos bila salah satu izin yang dimiliki bersifat baca`() {
        val authz = checker(guard = FixedLockGuard(locked = true))

        assertThat(authz.canAny("customer.customer.create", "customer.customer.view")).isTrue()
    }

    @Test
    fun `canAny yang semua izinnya menulis ikut terkunci`() {
        val authz = checker(guard = FixedLockGuard(locked = true))

        assertThatThrownBy { authz.canAny("customer.customer.create", "iam.user.create") }
            .isInstanceOf(SubscriptionLockedException::class.java)
    }

    @Test
    fun `canAll terkunci bila ada satu saja izin tulis di dalamnya`() {
        val authz = checker(guard = FixedLockGuard(locked = true))

        assertThatThrownBy { authz.canAll("customer.customer.view", "customer.customer.create") }
            .isInstanceOf(SubscriptionLockedException::class.java)
    }

    @Test
    fun `penjaga yang tak terkunci membiarkan semuanya lewat`() {
        val authz = checker(guard = FixedLockGuard(locked = false))

        assertThat(authz.can("customer.customer.create")).isTrue()
    }

    private fun checker(guard: ReadOnlyLockGuard?) =
        AccessChecker(FixedCurrentUser(permissions), FixedObjectProvider(guard))

    private class FixedLockGuard(private val locked: Boolean) : ReadOnlyLockGuard {
        override fun isReadOnly(): Boolean = locked
        override fun invalidate(tenantId: UUID) = Unit
    }

    private class FixedCurrentUser(private val permissions: Set<String>) : CurrentUserProvider {
        override fun currentOrNull(): AuthenticatedUser = AuthenticatedUser(
            userId = UuidV7.generate(),
            tenantId = UuidV7.generate(),
            email = "op@tenant.test",
            name = "Operator",
            platformAdmin = false,
            permissions = permissions,
            areaIds = emptySet(),
        )
    }
}
