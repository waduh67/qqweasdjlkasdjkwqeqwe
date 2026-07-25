package com.duluin.ftth.workorder.application.port.inbound

import java.util.UUID

/**
 * Mengangkat work order preventif dari sinyal sistem — bukan dari operator.
 *
 * Terpisah dari [ManageWorkOrderUseCase] justru karena tak ada pengguna di
 * baliknya: dipicu module lain (mis. monitoring saat redaman ONU memburuk) yang
 * berjalan tanpa konteks pengguna. Tenant diambil dari [com.duluin.ftth.common.tenant.TenantContext]
 * yang sudah dipasang pemanggil.
 */
interface RaisePreventiveMaintenanceUseCase {

    /**
     * Membuat WO preventif untuk ONU yang redamannya memburuk, bila ONU-nya
     * terpetakan ke pelanggan dan pelanggan itu belum punya WO preventif terbuka.
     *
     * @return id WO yang dibuat, atau `null` bila dilewati — ONU tak terpetakan ke
     *         pelanggan (perangkat liar) atau sudah ada WO preventif terbuka.
     */
    fun raiseForDegradingOnu(signal: DegradingOnuSignal): UUID?
}

/** Sinyal degradasi optik satu ONU, cukup untuk menjelaskan alasan WO-nya. */
data class DegradingOnuSignal(
    val onuId: UUID,
    val trendDbPerDay: Double,
    val averageRxPowerDbm: Double?,
    val minRxPowerDbm: Double?,
    val samples: Int,
)
