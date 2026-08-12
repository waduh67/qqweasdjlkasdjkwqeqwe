package com.duluin.ftth.cpe.application.port.outbound

import com.duluin.ftth.cpe.domain.model.CpeActionLog
import com.duluin.ftth.cpe.domain.model.CpeDevice
import java.util.UUID

/**
 * Port persistence proyeksi CPE. Kedua tabel tenant-aware (@TenantId + RLS), jadi
 * semua pencarian ter-scope tenant aktif secara otomatis — tak ada parameter
 * tenantId yang dibawa-bawa seperti pada `collector` yang sengaja tanpa RLS.
 */
interface CpeDeviceRepository {

    fun save(device: CpeDevice): CpeDevice

    fun findById(id: UUID): CpeDevice?

    fun findByGenieacsId(genieacsId: String): CpeDevice?

    fun findByCustomerId(customerId: UUID): List<CpeDevice>

    /** Seluruh proyeksi milik tenant aktif — dipakai sinkronisasi untuk memangkas. */
    fun findAllForCurrentTenant(): List<CpeDevice>

    /**
     * Sekumpulan device sekaligus — dipakai konsol ACS untuk memberi nama serial pada
     * baris log aktivitas tanpa satu query per baris.
     */
    fun findByIds(ids: Collection<UUID>): List<CpeDevice>

    fun deleteByIds(ids: Collection<UUID>)
}

interface CpeActionLogRepository {

    fun save(log: CpeActionLog): CpeActionLog

    /** Riwayat aksi satu device, terbaru dulu. */
    fun findByDeviceId(deviceId: UUID): List<CpeActionLog>

    /**
     * Aksi terbaru LINTAS device milik tenant aktif, terbaru dulu — jendela "View Logs"
     * di konsol ACS. Ter-scope RLS seperti pencarian lain di port ini, jadi log tenant
     * lain tak mungkin ikut walau tabelnya satu.
     */
    fun findRecentForCurrentTenant(limit: Int): List<CpeActionLog>
}
