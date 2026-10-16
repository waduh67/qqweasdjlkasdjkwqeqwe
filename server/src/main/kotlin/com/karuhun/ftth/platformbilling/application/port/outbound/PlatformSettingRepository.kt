package com.karuhun.ftth.platformbilling.application.port.outbound

import com.karuhun.ftth.platformbilling.domain.model.PlatformSetting

/** Akses setelan billing global platform (singleton, satu baris). */
interface PlatformSettingRepository {
    /** Baris setelan bila sudah ada; null bila platform belum pernah dikonfigurasi. */
    fun find(): PlatformSetting?
    fun save(setting: PlatformSetting): PlatformSetting
}
