package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.Odp
import java.util.UUID

interface OdpRepository {

    fun save(odp: Odp): Odp

    fun findById(id: UUID): Odp?

    fun findAllByIds(ids: Set<UUID>): List<Odp>

    /**
     * Seluruh ODP dalam batasan area — untuk heatmap utilisasi port di peta.
     * Tidak dipaginasi: heatmap justru butuh melihat semua ODP sekaligus saat
     * zoom-out.
     *
     * @param areaIds `null` berarti tanpa pembatasan area; set kosong berarti
     *        pengguna tidak punya area sama sekali sehingga hasilnya kosong.
     */
    fun findAllInAreas(areaIds: Set<UUID>?): List<Odp>

    /**
     * @param areaIds `null` berarti tanpa pembatasan area; set kosong berarti
     *        pengguna tidak punya area sama sekali sehingga hasilnya kosong.
     */
    fun search(query: String, areaIds: Set<UUID>?, odcId: UUID?, pageRequest: PageRequest): Page<Odp>

    fun findByOdcId(odcId: UUID): List<Odp>

    fun existsByCode(code: String): Boolean

    fun countByOdcId(odcId: UUID): Long

    /** Jumlah ODP per ODC dalam satu query — menghindari N+1 di daftar ODC. */
    fun countByOdcIds(odcIds: Set<UUID>): Map<UUID, Long>

    /** Id ODP yang menggantung pada salah satu ODC tersebut. */
    fun findIdsByOdcIds(odcIds: Set<UUID>): Set<UUID>

    /**
     * Kotak yang berdiri dalam radius sekian meter dari sebuah titik — dipakai
     * survey calon pelanggan: yang menentukan bisa-tidaknya sebuah alamat dilayani
     * adalah jarak fisik ke kotak terdekat, bukan keanggotaan area di peta.
     *
     * Radius diukur pada `geography` supaya satuannya meter sejati; di lintang
     * Indonesia satu derajat bujur ±111 km, dan radius yang salah satuan akan
     * menyapu seluruh kota.
     */
    fun findNear(location: Coordinate, radiusMeters: Double): List<Odp>

    fun deleteById(id: UUID)
}
