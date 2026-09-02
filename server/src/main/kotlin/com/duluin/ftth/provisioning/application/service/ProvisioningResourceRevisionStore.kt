package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ProvisioningResourceRevisionStore(private val entityManager: EntityManager) {
    fun current(type: String, id: UUID): Int {
        val values = entityManager.createNativeQuery(
            "SELECT revision FROM provisioning_resource_revision WHERE resource_type = :type AND resource_id = :id",
        ).setParameter("type", type).setParameter("id", id).resultList
        return (values.singleOrNull() as? Number)?.toInt() ?: 1
    }

    fun register(type: String, id: UUID) {
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_resource_revision (tenant_id, resource_type, resource_id, revision)
               VALUES (current_setting('app.tenant_id')::uuid, :type, :id, 1) ON CONFLICT DO NOTHING""",
        ).setParameter("type", type).setParameter("id", id).executeUpdate()
    }

    fun advance(type: String, id: UUID, expected: Int): Int {
        register(type, id)
        val updated = entityManager.createNativeQuery(
            """UPDATE provisioning_resource_revision SET revision = revision + 1, updated_at = now()
               WHERE resource_type = :type AND resource_id = :id AND revision = :expected
               RETURNING revision""",
        ).setParameter("type", type).setParameter("id", id).setParameter("expected", expected).resultList
        return (updated.singleOrNull() as? Number)?.toInt() ?: throw ConflictException("STALE_REVISION")
    }

    fun remove(type: String, id: UUID, expected: Int) {
        advance(type, id, expected)
        entityManager.createNativeQuery(
            "DELETE FROM provisioning_resource_revision WHERE resource_type = :type AND resource_id = :id",
        ).setParameter("type", type).setParameter("id", id).executeUpdate()
    }
}
