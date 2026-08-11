package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.network.domain.model.ClosureKind
import java.util.UUID

interface ManageSplitterUseCase {

    /** Isi satu kabinet ODC/ODP — modul demi modul, urut kodenya. */
    fun list(ownerKind: ClosureKind, ownerId: UUID): ClosureSplitterView

    fun create(command: SaveSplitterCommand): SplitterView

    /** Ganti rasio/catatan. Menurunkan rasio ditolak bila ada kaki terpakai di luar rasio baru. */
    fun update(id: UUID, command: UpdateSplitterCommand): SplitterView

    fun delete(id: UUID)
}

data class SaveSplitterCommand(
    val ownerKind: ClosureKind,
    val ownerId: UUID,
    /** Kosong = dinomori otomatis (SPL-1, SPL-2, …) mengikuti isi kabinetnya. */
    val code: String?,
    val ratio: String,
    val note: String?,
)

data class UpdateSplitterCommand(
    val ratio: String,
    val note: String?,
)
