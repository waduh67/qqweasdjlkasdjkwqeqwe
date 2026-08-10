package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.FiberConnection
import java.util.UUID

interface FiberConnectionRepository {

    fun findById(id: UUID): FiberConnection?

    /** Semua sambungan di dalam sebuah closure — isi kotak yang dibuka teknisi. */
    fun findByClosureId(closureId: UUID): List<FiberConnection>

    /** Sambungan yang menyentuh salah satu core ini, di closure mana pun. */
    fun findByCoreIds(coreIds: Collection<UUID>): List<FiberConnection>

    /**
     * Pemakai core ini DI DALAM closure tersebut. Bercakupan closure karena
     * sehelai core punya dua ujung: ujung ODC dan ujung ODP disambung di kotak
     * yang berbeda, dan keduanya sah.
     */
    fun findByCoreInClosure(closureId: UUID, coreId: UUID): FiberConnection?

    /**
     * Pemakai sebuah titik non-core, dicari GLOBAL. Kaki splitter, port ODF, PON
     * port, dan ONU hanya ada satu-satunya di seluruh jaringan, jadi tak perlu —
     * dan tak boleh — dibatasi per closure.
     */
    fun findByNodePoint(kind: ConnectionPointKind, nodeId: UUID, portNumber: Int?): FiberConnection?

    /** Sambungan yang menyentuh core milik kabel ini. */
    fun findByCableId(cableId: UUID): List<FiberConnection>

    fun save(connection: FiberConnection): FiberConnection

    fun deleteAll(connections: List<FiberConnection>)
}
