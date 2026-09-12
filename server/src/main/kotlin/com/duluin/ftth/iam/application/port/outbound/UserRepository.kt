package com.duluin.ftth.iam.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import java.util.UUID

/** Port persistence untuk agregat [User]. Query ter-scope tenant otomatis (Hibernate + RLS). */
interface UserRepository {

    fun save(user: User): User

    fun findById(id: UUID): User?

    /** Muat sekumpulan user sekaligus (mis. saat menampilkan nama teknisi di daftar work order). */
    fun findAllByIds(ids: Set<UUID>): List<User>

    /**
     * Semua pemegang sebuah peran di tenant aktif, TERMASUK yang sudah nonaktif.
     *
     * Yang nonaktif SENGAJA ikut dikembalikan: pemanggilnya (matriks persetujuan gudang) harus
     * bisa membedakan "peran ini memang belum diisi siapa pun" dari "pemegangnya ada tapi sudah
     * resign". Kalau di sini sudah disaring, kedua keadaan itu sampai ke pemanggil sebagai
     * daftar kosong yang sama persis, dan permintaan restock akan menggantung sampai kedaluwarsa
     * dengan pesan yang tak menolong siapa pun.
     */
    fun findAllByRoleId(roleId: UUID): List<User>

    fun findByEmail(email: Email): User?

    fun existsByEmail(email: Email): Boolean

    fun search(query: String?, pageRequest: PageRequest): Page<User>

    fun deleteById(id: UUID)
}
