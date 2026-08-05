package com.duluin.ftth.portal

import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.portal.application.service.PortalCredentialService
import com.duluin.ftth.portal.security.PortalCustomer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Menguji aturan kelola kredensial portal dengan fake in-memory:
 *  - login default = kode pelanggan; password kosong → generate sekali-tampil.
 *  - login unik per tenant (tolak bila dipegang pelanggan lain).
 *  - reset/nonaktif mencabut seluruh refresh-token.
 *  - ganti password mandiri memverifikasi password lama & panjang minimum.
 */
class PortalCredentialServiceTest {

    private val tenantId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private val credentials = InMemoryPortalCredentialRepository()
    private val refreshTokens = RecordingPortalRefreshTokenRepository()
    private val hasher = PlainTextPortalPasswordHasher()
    private val customers = StubCustomerApi(
        CustomerRef(customerId, "CUST-000007", "Budi", "0811", Coordinate(0.0, 0.0), "ACTIVE"),
    )
    private val currentPortal = MutableCurrentPortalCustomer()
    private val currentUser = NoOperatorCurrentUserProvider()

    private val service = PortalCredentialService(
        credentials, refreshTokens, hasher, customers, currentPortal, currentUser, { /* audit no-op */ },
    )

    // Operator memanggil dalam request terautentikasi → tenant sudah terpasang di context (RLS).
    @BeforeEach
    fun setTenant() = TenantContext.set(tenantId)

    @AfterEach
    fun clearTenant() = TenantContext.clear()

    @Test
    fun `provision tanpa login pakai kode pelanggan & generate password sekali-tampil`() {
        val result = service.provisionFor(customerId, login = null, password = null)

        assertThat(result.login).isEqualTo("cust-000007") // kode di-normalize lower-case
        assertThat(result.active).isTrue()
        assertThat(result.temporaryPassword).isNotNull()
        assertThat(result.temporaryPassword!!.length).isGreaterThanOrEqualTo(8)
        // Password tersimpan sebagai hash yang cocok dengan yang dibagikan.
        val stored = credentials.findByCustomerId(customerId)!!
        assertThat(hasher.matches(result.temporaryPassword!!, stored.passwordHash)).isTrue()
    }

    @Test
    fun `provision dengan password operator tak membocorkan temporaryPassword`() {
        val result = service.provisionFor(customerId, login = "budi01", password = "rahasia123")

        assertThat(result.login).isEqualTo("budi01")
        assertThat(result.temporaryPassword).isNull()
    }

    @Test
    fun `provision menolak password terlalu pendek`() {
        assertThatThrownBy { service.provisionFor(customerId, login = null, password = "short") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `provision menolak login yang dipegang pelanggan lain`() {
        val other = UUID.randomUUID()
        customers.add(CustomerRef(other, "CUST-000008", "Siti", null, Coordinate(0.0, 0.0), "ACTIVE"))
        service.provisionFor(other, login = "shared", password = "rahasia123")

        assertThatThrownBy { service.provisionFor(customerId, login = "shared", password = "rahasia123") }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `provision ulang untuk pemilik yang sama boleh menimpa login sama`() {
        service.provisionFor(customerId, login = "budi01", password = "rahasia123")
        val again = service.provisionFor(customerId, login = "budi01", password = "rahasia456")

        assertThat(again.login).isEqualTo("budi01")
        assertThat(credentials.count()).isEqualTo(1) // tetap satu kredensial
    }

    @Test
    fun `reset password mencabut seluruh refresh-token`() {
        service.provisionFor(customerId, login = "budi01", password = "rahasia123")
        refreshTokens.revokedCustomers.clear()

        val result = service.resetPassword(customerId, newPassword = null)

        assertThat(result.temporaryPassword).isNotNull()
        assertThat(refreshTokens.revokedCustomers).containsExactly(customerId)
    }

    @Test
    fun `reset password menolak pelanggan tanpa kredensial`() {
        assertThatThrownBy { service.resetPassword(customerId, newPassword = "rahasia123") }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `nonaktifkan mencabut token dan mematikan login sedang aktifkan tidak`() {
        service.provisionFor(customerId, login = "budi01", password = "rahasia123")
        refreshTokens.revokedCustomers.clear()

        val disabled = service.setEnabled(customerId, enabled = false)
        assertThat(disabled.active).isFalse()
        assertThat(refreshTokens.revokedCustomers).containsExactly(customerId)

        refreshTokens.revokedCustomers.clear()
        val enabled = service.setEnabled(customerId, enabled = true)
        assertThat(enabled.active).isTrue()
        assertThat(refreshTokens.revokedCustomers).isEmpty()
    }

    @Test
    fun `ganti password mandiri memverifikasi password lama & mencabut sesi`() {
        service.provisionFor(customerId, login = "budi01", password = "rahasia123")
        currentPortal.value = PortalCustomer(customerId, tenantId, "budi01", "Budi")
        refreshTokens.revokedCustomers.clear()

        service.changeOwnPassword(currentPassword = "rahasia123", newPassword = "rahasiaBaru9")

        val stored = credentials.findByCustomerId(customerId)!!
        assertThat(hasher.matches("rahasiaBaru9", stored.passwordHash)).isTrue()
        assertThat(refreshTokens.revokedCustomers).containsExactly(customerId)
    }

    @Test
    fun `ganti password mandiri menolak password lama salah`() {
        service.provisionFor(customerId, login = "budi01", password = "rahasia123")
        currentPortal.value = PortalCustomer(customerId, tenantId, "budi01", "Budi")

        assertThatThrownBy { service.changeOwnPassword("salah", "rahasiaBaru9") }
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `ganti password mandiri menolak password baru terlalu pendek`() {
        service.provisionFor(customerId, login = "budi01", password = "rahasia123")
        currentPortal.value = PortalCustomer(customerId, tenantId, "budi01", "Budi")

        assertThatThrownBy { service.changeOwnPassword("rahasia123", "short") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `summaryFor null bila belum diprovisi`() {
        assertThat(service.summaryFor(customerId)).isNull()
    }
}
