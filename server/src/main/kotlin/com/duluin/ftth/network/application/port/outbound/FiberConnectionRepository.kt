package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.FiberConnection
import com.duluin.ftth.network.domain.model.OdfPortSide
import java.util.UUID

interface FiberConnectionRepository {

    fun findById(id: UUID): FiberConnection?

    /** Semua sambungan di dalam sebuah closure — isi kotak yang dibuka teknisi. */
    fun findByClosureId(closureId: UUID): List<FiberConnection>

    /** Isi kotak tanpa memuatnya: untuk batas kapasitas dan angka "12/24" di daftar. */
    fun countByClosureId(closureId: UUID): Long

    /** Isi banyak kotak sekaligus — satu query untuk satu halaman daftar. */
    fun countByClosureIds(closureIds: Set<UUID>): Map<UUID, Long>

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
     *
     * [portSide] ikut jadi bagian identitas untuk port ODF: yang boleh dipakai
     * sekali adalah SISI, bukan portnya. Null untuk titik jenis lain.
     */
    fun findByNodePoint(
        kind: ConnectionPointKind,
        nodeId: UUID,
        portNumber: Int?,
        portSide: OdfPortSide? = null,
    ): FiberConnection?

    /**
     * Berapa port berbeda di simpul ini yang sudah terpakai — angka "sisa slot"
     * untuk rak ODF. Satu port yang kedua sisinya tersambung tetap dihitung satu.
     */
    fun countUsedPortsOfNode(kind: ConnectionPointKind, nodeId: UUID): Long

    /**
     * NOMOR port yang terpakai di tiap simpul, bukan sekadar jumlahnya — yang
     * ditanya di depan kabinet adalah "kaki mana yang masih kosong", dan jumlah
     * saja tak menjawabnya begitu ada kaki yang dilepas di tengah.
     */
    fun usedPortNumbersOfNodes(kind: ConnectionPointKind, nodeIds: Set<UUID>): Map<UUID, Set<Int>>

    /**
     * Simpul yang titik TAK-BERNOMOR-nya sudah tersambung, mis. input splitter.
     * Terpisah dari [usedPortNumbersOfNodes] karena titik semacam itu memang tak
     * punya nomor untuk dikumpulkan.
     */
    fun nodesWithPoint(kind: ConnectionPointKind, nodeIds: Set<UUID>): Set<UUID>

    /** Versi banyak-simpul, satu query untuk satu halaman daftar. */
    fun countUsedPortsOfNodes(kind: ConnectionPointKind, nodeIds: Set<UUID>): Map<UUID, Long>

    /**
     * Sambungan yang menyentuh titik jenis [kind] pada salah satu [nodeIds] — apa
     * adanya, bukan sekadar hitungannya.
     *
     * Bedanya dengan [usedPortNumbersOfNodes]: layar splicing perlu tahu bukan
     * cuma "kaki 3 terpakai", melainkan TERPAKAI OLEH APA, supaya tombol "lepas"
     * di sebelah kaki itu punya sesuatu untuk dilepas.
     */
    fun findByNodeIds(kind: ConnectionPointKind, nodeIds: Set<UUID>): List<FiberConnection>

    /** Sambungan yang menyentuh core milik kabel ini. */
    fun findByCableId(cableId: UUID): List<FiberConnection>

    /**
     * Pekerjaan serat yang dibukukan ke sebuah work order, terlama dulu.
     *
     * Inilah yang dibaca penyelia saat memeriksa hasil kerja: bukan "tiketnya
     * ditutup", melainkan kotak mana saja yang dibuka dan apa yang disambung di
     * dalamnya.
     */
    fun findByWorkOrderId(workOrderId: UUID): List<FiberConnection>

    fun save(connection: FiberConnection): FiberConnection

    fun deleteAll(connections: List<FiberConnection>)
}
