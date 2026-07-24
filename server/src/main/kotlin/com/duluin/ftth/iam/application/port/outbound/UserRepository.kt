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

    fun findByEmail(email: Email): User?

    fun existsByEmail(email: Email): Boolean

    fun search(query: String?, pageRequest: PageRequest): Page<User>

    fun deleteById(id: UUID)
}
