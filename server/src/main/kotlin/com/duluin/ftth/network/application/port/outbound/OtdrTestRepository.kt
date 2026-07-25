package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.network.domain.model.OtdrTest
import java.util.UUID

interface OtdrTestRepository {

    fun save(test: OtdrTest): OtdrTest

    fun findById(id: UUID): OtdrTest?

    /** Riwayat uji sebuah kabel, terbaru dulu. */
    fun listByCable(cableId: UUID): List<OtdrTest>

    fun deleteById(id: UUID)
}
