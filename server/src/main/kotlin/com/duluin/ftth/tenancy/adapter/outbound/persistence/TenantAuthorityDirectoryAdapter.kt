package com.duluin.ftth.tenancy.adapter.outbound.persistence

import com.duluin.ftth.tenancy.TenantAuthorityDirectory
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.util.UUID

@Repository
class TenantAuthorityDirectoryAdapter(private val entityManager: EntityManager) : TenantAuthorityDirectory {
    override fun allTenantIds(): List<UUID> = entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
        connection.createStatement().use { statement -> statement.executeQuery("SELECT id FROM tenant ORDER BY id").use { rows ->
            buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) }
        } }
    }
}
