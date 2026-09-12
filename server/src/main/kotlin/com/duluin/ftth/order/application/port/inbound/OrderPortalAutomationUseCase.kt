package com.duluin.ftth.order.application.port.inbound

import com.duluin.ftth.order.domain.model.OrderPortalFlag
import com.duluin.ftth.order.domain.model.OrderPortalNarrative
import java.util.UUID

/**
 * Pemasangan penanda portal oleh OTOMASI — bukan oleh manusia yang menekan tombol.
 *
 * Terpisah dari [OrderAttentionUseCase] karena kontraknya berbeda di dua hal yang justru paling
 * penting:
 *
 * 1. **Tidak pernah melempar untuk keadaan yang wajar.** Pemanggilnya adalah listener event dan
 *    koordinator saga. Kalau ini melempar saat pesanannya sudah selesai atau sudah bertanda
 *    operator, transaksi PEMANGGILNYA ikut batal — checkpoint saga yang seharusnya tersimpan
 *    sebagai REQUIRES_RECONCILIATION malah hilang, dan pesanannya jadi LEBIH tak terlihat
 *    daripada sebelum fitur ini ada. Dan di Spring kegagalan itu tak bisa sekadar "ditangkap":
 *    begitu bean ber-@Transactional melempar, TransactionInterceptor sudah memanggil
 *    setRollbackOnly() dan commit pemanggil meledak jadi UnexpectedRollbackException.
 *
 * 2. **Kalimatnya dipilih dari daftar**, bukan diketik. Lihat [OrderPortalNarrative].
 */
interface OrderPortalAutomationUseCase {
    /** @return true kalau penanda benar-benar berubah; false kalau sengaja tidak diapa-apakan. */
    fun applySystemFlag(command: SystemFlagCommand): Boolean

    /**
     * Lepas penanda yang dipasang SISTEM sendiri. Penanda operator TIDAK PERNAH disentuh —
     * lihat [com.duluin.ftth.order.domain.model.OrderPortalFlagSource].
     *
     * @return true kalau penanda benar-benar dilepas.
     */
    fun releaseSystemFlag(command: ReleaseSystemFlagCommand): Boolean
}

/**
 * [tenantId] dikirim eksplisit karena pemanggilnya sering berjalan tanpa `SecurityContext`
 * (worker outbox, listener event): `currentUser.current()` di sana melempar, dan penanda yang
 * gagal dipasang karena tak ada principal adalah kegagalan yang tak terlihat sama sekali.
 *
 * [actorId] boleh null untuk sumber yang memang tak punya pelaku manusia.
 *
 * [reference] menjadi bagian `operationKey` supaya satu sumber kejadian (satu kunjungan, satu
 * checkpoint saga) tidak menulis riwayat berkali-kali ketika sumbernya diulang.
 */
data class SystemFlagCommand(
    val tenantId: UUID,
    val orderId: UUID,
    val flag: OrderPortalFlag,
    val narrative: OrderPortalNarrative,
    val source: String,
    val reference: String,
    val actorId: UUID? = null,
)

data class ReleaseSystemFlagCommand(
    val tenantId: UUID,
    val orderId: UUID,
    val flag: OrderPortalFlag,
    val source: String,
    val reference: String,
    val actorId: UUID? = null,
)
