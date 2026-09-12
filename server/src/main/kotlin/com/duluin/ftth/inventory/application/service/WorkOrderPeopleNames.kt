package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.IamApi
import java.util.UUID

/**
 * Kamus nama orang untuk read model material work order — dibangun SEKALI per permintaan.
 *
 * Alasannya sama persis dengan `InventoryStockQueryService.NameBook`: layar material WO dibaca
 * teknisi lapangan dan petugas gudang, dan keduanya TIDAK punya `iam.user.view`. Selama server
 * hanya memulangkan UUID, klien terpaksa menggabungkan sendiri ke `/api/users`, kena 403, lalu
 * menampilkan UUID telanjang tepat di layar yang jadi pekerjaan sehari-hari mereka. Resolusi di
 * sini berjalan IN-PROCESS lewat [IamApi] yang tidak punya `@PreAuthorize`, jadi tidak ada
 * pemeriksaan izin direktori pengguna yang perlu dilewati sama sekali.
 *
 * Dibuat lewat [resolve] dengan SATU himpunan id yang sudah lengkap, bukan per baris. Satu WO
 * bisa punya belasan baris material dengan puluhan serial yang masing-masing punya `scannedBy`;
 * menanyakan namanya satu per satu adalah N+1 yang tumbuh persis seiring ramainya WO.
 *
 * Scope tenant-nya diwarisi dari [IamApi] (RLS): id milik tenant lain memang tidak menemukan
 * barisnya dan jatuh ke fallback UUID — bukan membocorkan nama orang tenant sebelah.
 */
internal class WorkOrderPeopleNames private constructor(private val names: Map<UUID, String>) {

    /**
     * Nama seorang pengguna; fallback ke UUID-nya, BUKAN string kosong.
     *
     * Sel kosong di layar terbaca "tidak ada orangnya" — padahal yang terjadi adalah id yang tidak
     * teresolusi (pengguna terhapus, atau id milik tenant lain). UUID yang tampil jelek itu justru
     * petunjuk yang bisa ditindaklanjuti.
     */
    fun person(id: UUID): String = names[id] ?: id.toString()

    /** `null` HANYA kalau id sumbernya memang null — bukan kalau namanya gagal diresolusi. */
    fun personOrNull(id: UUID?): String? = id?.let { person(it) }

    companion object {
        /** Himpunan kosong TIDAK memanggil [IamApi] sama sekali. */
        fun resolve(iam: IamApi, ids: Set<UUID>): WorkOrderPeopleNames = WorkOrderPeopleNames(
            if (ids.isEmpty()) emptyMap() else iam.usersByIds(ids).associate { it.id to it.name },
        )
    }
}
