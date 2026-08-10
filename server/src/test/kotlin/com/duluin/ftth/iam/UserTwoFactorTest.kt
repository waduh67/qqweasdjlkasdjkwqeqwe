package com.duluin.ftth.iam

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** Invarian agregat User seputar faktor kedua — aturan yang tak boleh bocor ke service. */
class UserTwoFactorTest {

    private fun newUser() = User.create(
        tenantId = UUID.randomUUID(),
        email = Email.of("op@contoh.test"),
        name = "Operator",
        passwordHash = "hash",
    )

    @Test
    fun `user baru belum punya faktor kedua`() {
        val user = newUser()
        assertThat(user.twoFactorEnabled).isFalse()
        assertThat(user.totpSecret).isNull()
    }

    @Test
    fun `pendaftaran yang belum dikonfirmasi belum menghitung 2FA aktif`() {
        val user = newUser()
        user.beginTotpEnrollment("v1:terenkripsi")

        // Salah pindai QR lalu menutup halaman TIDAK boleh mengunci orangnya di luar akun.
        assertThat(user.totpSecret).isEqualTo("v1:terenkripsi")
        assertThat(user.twoFactorEnabled).isFalse()
    }

    @Test
    fun `mendaftar ulang saat 2FA aktif ditolak`() {
        val user = newUser()
        user.beginTotpEnrollment("v1:pertama")
        user.confirmTotp(step = 100)

        assertThatThrownBy { user.beginTotpEnrollment("v1:kedua") }
            .isInstanceOf(ConflictException::class.java)
        assertThat(user.totpSecret).isEqualTo("v1:pertama")
    }

    @Test
    fun `konfirmasi tanpa pendaftaran yang menunggu ditolak`() {
        assertThatThrownBy { newUser().confirmTotp(step = 100) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `langkah waktu yang sama atau lebih tua ditolak sebagai pemutaran ulang`() {
        val user = newUser()
        user.beginTotpEnrollment("v1:rahasia")
        user.confirmTotp(step = 100)

        assertThat(user.acceptTotpStep(100)).isFalse()
        assertThat(user.acceptTotpStep(99)).isFalse()
        assertThat(user.acceptTotpStep(101)).isTrue()
        assertThat(user.acceptTotpStep(101)).isFalse()
        assertThat(user.totpLastStep).isEqualTo(101)
    }

    @Test
    fun `mematikan 2FA membuang seluruh jejaknya`() {
        val user = newUser()
        user.beginTotpEnrollment("v1:rahasia")
        user.confirmTotp(step = 100)

        user.disableTotp()

        assertThat(user.twoFactorEnabled).isFalse()
        assertThat(user.totpSecret).isNull()
        assertThat(user.totpEnabledAt).isNull()
        // Langkah terakhir ikut dibuang: rahasia berikutnya adalah rahasia BARU, dan
        // menyisakan batas lama akan menolak kode sah dari perangkat baru.
        assertThat(user.totpLastStep).isNull()
    }
}
