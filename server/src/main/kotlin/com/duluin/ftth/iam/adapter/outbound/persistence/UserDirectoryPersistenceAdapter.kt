package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.iam.application.port.outbound.UserDirectory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class UserDirectoryPersistenceAdapter(
    private val jpa: UserDirectoryJpaRepository,
) : UserDirectory {

    override fun findTenantByEmail(emailLower: String): UUID? =
        jpa.findByEmailLower(emailLower)?.tenantId
}
