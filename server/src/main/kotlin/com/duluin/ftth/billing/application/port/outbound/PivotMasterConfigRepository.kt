package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.PivotMasterConfig

/**
 * Penyimpanan setelan MASTER Pivot platform (singleton global, PLATFORM-level tanpa RLS).
 * [find] mengambil baris tunggal (null bila platform belum pernah mengonfigurasi Pivot).
 */
interface PivotMasterConfigRepository {
    fun find(): PivotMasterConfig?
    fun save(config: PivotMasterConfig): PivotMasterConfig
}
