package com.karuhun.ftth.network.application.port.outbound

import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.network.domain.model.Odf
import java.util.UUID

interface OdfRepository {

    fun save(odf: Odf): Odf

    fun findById(id: UUID): Odf?

    /** Sekumpulan rak dalam satu query — penawar N+1 di layar yang menyebut banyak kotak. */
    fun findAllByIds(ids: Set<UUID>): List<Odf>

    /**
     * @param areaIds `null` berarti tanpa pembatasan area; set kosong berarti
     *        pengguna tidak punya area sama sekali sehingga hasilnya kosong.
     */
    fun search(query: String, areaIds: Set<UUID>?, pageRequest: PageRequest): Page<Odf>

    fun existsByCode(code: String): Boolean

    /** Rak di sebuah POP — penjaga penghapusan site yang masih berisi rak. */
    fun countBySiteId(siteId: UUID): Long

    fun deleteById(id: UUID)
}
