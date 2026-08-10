package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.network.domain.model.CableCore
import java.util.UUID

interface CableCoreRepository {

    /** Semua core sebuah kabel, terurut nomor. */
    fun findByCableId(cableId: UUID): List<CableCore>

    fun saveAll(cores: List<CableCore>): List<CableCore>

    /**
     * Membuang core bernomor di atas [coreNumber] — dipakai saat jumlah core
     * sebuah kabel DIKURANGI. Pemanggil wajib memastikan dulu core yang dibuang
     * memang belum terpakai.
     */
    fun deleteAboveCoreNumber(cableId: UUID, coreNumber: Int)
}
