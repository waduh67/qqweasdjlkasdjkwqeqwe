package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import java.util.UUID

interface CableRepository {

    fun save(cable: Cable): Cable

    fun findById(id: UUID): Cable?

    fun findByIds(ids: Collection<UUID>): List<Cable>

    fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<Cable>

    /** Kabel yang BERUJUNG di simpul tertentu — bukan yang sekadar menyinggahinya. */
    fun findByEndpoint(node: NetworkNodeRef): List<Cable>

    /** Kabel yang salah satu UJUNG id-nya ada di [nodeIds] (abai jenis simpul). */
    fun findByEndpointNodeIds(nodeIds: Set<UUID>): List<Cable>

    /**
     * Kabel yang MENYENTUH sebuah simpul dalam peran apa pun: berujung di sana,
     * dikupas di sana, atau cuma melintas di dalam kotaknya.
     *
     * Inilah pertanyaan yang dipakai meja sambung tiap kali sebuah closure
     * dibuka, dan jawabannya dibaca dari catatan singgahan — perbuatan manusia
     * atas selubung — bukan ditebak dari jarak rute ke kotaknya. Lihat V99.
     */
    fun findAttachedTo(nodeId: UUID): List<Cable>

    /**
     * Kabel yang RUTENYA lewat maksimal [radiusMeters] dari [location].
     *
     * BUKAN untuk menentukan kabel mana yang bisa disambung di sebuah kotak —
     * itu ditentukan [findAttachedTo]. Ini pertanyaan survei: "kalau saya mau
     * pasang kotak di titik ini, kabel apa yang kira-kira lewat dekat sini?",
     * di mana jarak memang jawabannya. Dikerjakan di database supaya indeks
     * GiST yang menyaring, bukan seluruh kabel tenant dimuat ke memori.
     */
    fun findPassing(location: Coordinate, radiusMeters: Double): List<Cable>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
