package com.duluin.ftth.platformbilling.application.port.outbound

import com.duluin.ftth.platformbilling.domain.model.PlatformSetting

/** Akses setelan billing global platform (singleton, satu baris). */
interface PlatformSettingRepository {
    /** Baris setelan bila sudah ada; null bila platform belum pernah dikonfigurasi. */
    fun find(): PlatformSetting?
    fun save(setting: PlatformSetting): PlatformSetting
}
