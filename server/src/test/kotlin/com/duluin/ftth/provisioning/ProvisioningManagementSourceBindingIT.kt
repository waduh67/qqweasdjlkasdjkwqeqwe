package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningManagementSourceBindingIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var safetyEvidence: ProvisioningSafetyEvidenceRepository
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `same tenant topology source for another device cannot mark management evidence complete`() {
        val tenantId = tenantApi.ensureTenant("source-bind-${UUID.randomUUID()}", "source-bind").id
        val sourceId = UuidV7.generate()
        val sourceDeviceId = UuidV7.generate()
        val evidenceDeviceId = UuidV7.generate()
        val now = Instant.now()
        asTenant(tenantId) {
            em.createNativeQuery(
                """INSERT INTO provisioning_managed_node
                   (id, tenant_id, name, role, reference_kind, reference_id, administrative_status, observed_at)
                   VALUES (:id, :tenant, :name, 'BRAS', 'NAS', :device, 'ENABLED', :observed)""",
            ).setParameter("id", sourceId).setParameter("tenant", tenantId)
                .setParameter("name", "node-${UUID.randomUUID()}").setParameter("device", sourceDeviceId)
                .setParameter("observed", now).executeUpdate()
        }

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery(
                    """INSERT INTO provisioning_management_safety_evidence
                       (id, tenant_id, device_kind, device_id, protected_vlan_ranges, protected_ip_prefixes,
                        protected_vrfs, protected_interface_roles, protected_collector_paths, protected_oob_routes,
                        available_oob_routes, observed_at, valid_until, complete, source_type,
                        topology_source_id, device_observation_source_id)
                       VALUES (:id, :tenant, 'BRAS', :device, '', '', '', 'MANAGEMENT', '', '', '',
                        :observed, :valid, true, 'TOPOLOGY_OBSERVATION', :source, NULL)""",
                ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                    .setParameter("device", evidenceDeviceId).setParameter("observed", now)
                    .setParameter("valid", now.plusSeconds(300)).setParameter("source", sourceId).executeUpdate()
            }
        }
    }

    @Test
    fun `loader rejects evidence after topology source identity moves to another device`() {
        val tenantId = tenantApi.ensureTenant("source-revalidate-${UUID.randomUUID()}", "source-revalidate").id
        val sourceId = UuidV7.generate()
        val deviceId = UuidV7.generate()
        val now = Instant.now()
        asTenant(tenantId) {
            insertTopologySource(tenantId, sourceId, deviceId, now)
            insertManagementEvidence(tenantId, sourceId, deviceId, now)
            em.createNativeQuery("UPDATE provisioning_managed_node SET reference_id = :other WHERE id = :source")
                .setParameter("other", UuidV7.generate()).setParameter("source", sourceId).executeUpdate()
        }

        assertThat(
            asTenant(tenantId) {
                safetyEvidence.findManagementEvidence(tenantId, DeviceReference(DeviceKind.BRAS, deviceId)) == null
            },
        ).isTrue()
    }

    private fun insertTopologySource(tenantId: UUID, sourceId: UUID, deviceId: UUID, now: Instant) {
        em.createNativeQuery(
            """INSERT INTO provisioning_managed_node
               (id, tenant_id, name, role, reference_kind, reference_id, administrative_status, observed_at)
               VALUES (:id, :tenant, :name, 'BRAS', 'NAS', :device, 'ENABLED', :observed)""",
        ).setParameter("id", sourceId).setParameter("tenant", tenantId)
            .setParameter("name", "node-${UUID.randomUUID()}").setParameter("device", deviceId)
            .setParameter("observed", now).executeUpdate()
    }

    private fun insertManagementEvidence(tenantId: UUID, sourceId: UUID, deviceId: UUID, now: Instant) {
        em.createNativeQuery(
            """INSERT INTO provisioning_management_safety_evidence
               (id, tenant_id, device_kind, device_id, protected_vlan_ranges, protected_ip_prefixes,
                protected_vrfs, protected_interface_roles, protected_collector_paths, protected_oob_routes,
                available_oob_routes, observed_at, valid_until, complete, source_type,
                topology_source_id, device_observation_source_id)
               VALUES (:id, :tenant, 'BRAS', :device, '', '', '', 'MANAGEMENT', '', '', '',
                :observed, :valid, true, 'TOPOLOGY_OBSERVATION', :source, NULL)""",
        ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
            .setParameter("device", deviceId).setParameter("observed", now)
            .setParameter("valid", now.plusSeconds(300)).setParameter("source", sourceId).executeUpdate()
    }

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }
}
