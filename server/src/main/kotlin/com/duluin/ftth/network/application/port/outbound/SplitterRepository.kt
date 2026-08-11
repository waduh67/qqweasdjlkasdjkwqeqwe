package com.duluin.ftth.network.application.port.outbound

import com.duluin.ftth.network.domain.model.Splitter
import java.util.UUID

interface SplitterRepository {

    fun findById(id: UUID): Splitter?

    /** Isi sebuah kabinet, urut kode — persis urutan modul di raknya. */
    fun findByOwnerId(ownerId: UUID): List<Splitter>

    /** Isi banyak kabinet sekaligus: satu query untuk satu halaman daftar. */
    fun findByOwnerIds(ownerIds: Set<UUID>): Map<UUID, List<Splitter>>

    /** Kode splitter unik DI DALAM kabinetnya, bukan se-tenant. */
    fun existsByOwnerIdAndCode(ownerId: UUID, code: String): Boolean

    fun save(splitter: Splitter): Splitter

    fun deleteById(id: UUID)

    fun deleteAll(splitters: List<Splitter>)
}
