package com.duluin.ftth.common.infrastructure.security

import com.duluin.ftth.common.domain.error.SubscriptionLockedException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.ReadOnlyLockGuard
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Dipakai di anotasi keamanan method: `@PreAuthorize("@authz.can('iam.role.create')")`.
 *
 * Terpusat di satu tempat sehingga aturan "platform admin melewati semua izin"
 * konsisten, dan pengecekan izin terbaca jelas di setiap use case.
 *
 * Sejak kunci baca-saja, tempat ini juga yang menegakkan tunggakan langganan SaaS. Bukan
 * karena "kebetulan lewat sini", melainkan karena inilah SATU-SATUNYA gerbang yang dilewati
 * hampir setiap operasi tulis di aplikasi ini, dan konvensi penamaan izinnya konsisten:
 * `*.view` membaca, sisanya menulis (lihat `PermissionCatalog`). Alternatifnya — menyaring
 * berdasarkan method HTTP — salah menuduh endpoint POST yang sebenarnya cuma membaca
 * (pencarian, pratinjau, ekspor), dan tak menyentuh jalur non-HTTP sama sekali.
 */
@Component("authz")
class AccessChecker(
    private val currentUser: CurrentUserProvider,
    /**
     * `ObjectProvider` supaya konteks tanpa module platformbilling (test unit, potongan
     * aplikasi) tetap berjalan dengan perilaku lama: tak ada penjaga = tak ada yang terkunci.
     */
    private val lockGuard: ObjectProvider<ReadOnlyLockGuard>,
) {
    fun can(permissionCode: String): Boolean {
        val user = currentUser.currentOrNull() ?: return false
        if (!user.hasPermission(permissionCode)) return false
        assertNotLocked(permissionCode)
        return true
    }

    /** True bila SEMUA izin dimiliki. */
    fun canAll(vararg permissionCodes: String): Boolean {
        val user = currentUser.currentOrNull() ?: return false
        if (!permissionCodes.all(user::hasPermission)) return false
        permissionCodes.forEach(::assertNotLocked)
        return true
    }

    /** True bila MINIMAL SATU izin dimiliki. */
    fun canAny(vararg permissionCodes: String): Boolean {
        val user = currentUser.currentOrNull() ?: return false
        val held = permissionCodes.filter(user::hasPermission)
        if (held.isEmpty()) return false
        // Cukup satu izin yang lolos: kalau di antara yang dimiliki ada izin baca, operasinya
        // memang bisa dijalankan dalam mode baca-saja dan tak ada alasan menolaknya.
        if (held.any { !isWrite(it) }) return true
        assertNotLocked(held.first())
        return true
    }

    fun isPlatformAdmin(): Boolean = currentUser.currentOrNull()?.platformAdmin ?: false

    /**
     * MELEMPAR, bukan mengembalikan false. Dua keadaan ini menuntut jawaban berbeda dari
     * pengguna: "izinmu kurang" (403) berarti hubungi admin, "langgananmu menunggak" (402)
     * berarti bayar — dan false yang sama untuk keduanya menyembunyikan bedanya. Exception dari
     * dalam SpEL `@PreAuthorize` merambat utuh ke `GlobalExceptionHandler`.
     */
    private fun assertNotLocked(permissionCode: String) {
        if (!isWrite(permissionCode)) return
        if (permissionCode in ALWAYS_ALLOWED) return
        if (lockGuard.getIfAvailable()?.isReadOnly() == true) throw SubscriptionLockedException()
    }

    private fun isWrite(permissionCode: String): Boolean = !permissionCode.endsWith(VIEW_SUFFIX)

    private companion object {
        /** Konvensi `PermissionCatalog`: hanya izin berakhiran ini yang murni membaca. */
        const val VIEW_SUFFIX = ".view"

        /**
         * Izin tulis yang TETAP hidup saat terkunci. Tanpa ini kuncinya menelan dirinya sendiri:
         * tenant yang menunggak tak bisa membayar tunggakan yang membuka kuncinya.
         */
        val ALWAYS_ALLOWED = setOf("billing.subscription.renew")
    }
}
