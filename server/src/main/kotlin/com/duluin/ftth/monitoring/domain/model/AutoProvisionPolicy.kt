package com.duluin.ftth.monitoring.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.util.UUID

/**
 * Kebijakan auto-provisioning zero-touch untuk satu tenant.
 *
 * Bila [enabled], ONU liar yang teresolusi ke keyakinan HIGH ditautkan otomatis
 * ke {pelanggan, ODP, port}-nya tanpa menunggu operator menekan "Terima". Bila
 * mati, tebakan tetap hanya mengisi form di muka — persis perilaku sebelum fitur
 * ini ada.
 *
 * Default MATI, dan sengaja: menautkan ONU otomatis memutasi data pelanggan
 * tanpa mata manusia, jadi tenant harus menyalakannya dengan sadar. Tak ada
 * barisnya = mati — [defaultFor] menghasilkan kebijakan mati, mengikuti pola
 * [AlarmRule.defaultFor] agar tabel hanya berisi hal yang memang sengaja disetel.
 */
class AutoProvisionPolicy private constructor(
    val id: UUID,
    val tenantId: UUID,
    enabled: Boolean,
) {
    var enabled: Boolean = enabled
        private set

    fun update(enabled: Boolean) {
        this.enabled = enabled
    }

    companion object {
        fun rehydrate(id: UUID, tenantId: UUID, enabled: Boolean): AutoProvisionPolicy =
            AutoProvisionPolicy(id, tenantId, enabled)

        /** Kebijakan bawaan untuk tenant yang belum pernah menyetel — zero-touch MATI. */
        fun defaultFor(tenantId: UUID): AutoProvisionPolicy =
            AutoProvisionPolicy(UuidV7.generate(), tenantId, enabled = false)
    }
}
