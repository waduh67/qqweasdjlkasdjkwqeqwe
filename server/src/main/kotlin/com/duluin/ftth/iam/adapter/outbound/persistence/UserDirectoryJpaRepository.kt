package com.duluin.ftth.iam.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserDirectoryJpaRepository : JpaRepository<UserDirectoryJpaEntity, UUID> {

    fun findByEmailLower(emailLower: String): UserDirectoryJpaEntity?
}
