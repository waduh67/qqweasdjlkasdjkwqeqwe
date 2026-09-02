package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
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
class ProvisioningCertificationProvenanceIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `database rejects certification whose capability evidence has a different exact fingerprint`() {
        val tenantId = tenantApi.ensureTenant("cert-fk-${UUID.randomUUID()}", "cert-fk").id
        val collectorId = UuidV7.generate()
        val evidenceId = UuidV7.generate()
        val reportId = UuidV7.generate()
        val evidenceDeviceId = UuidV7.generate()
        val certifiedDeviceId = UuidV7.generate()
        val now = Instant.now()
        asTenant(tenantId) {
            insertCollector(tenantId, collectorId)
            insertCapability(tenantId, collectorId, reportId, evidenceId, evidenceDeviceId, now)
        }

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery(
                    """INSERT INTO provisioning_adapter_certification
                       (id, tenant_id, device_kind, device_id, vendor, model, firmware, transport, operation_class,
                        status, valid_until, evidence_id, certified_by, certified_at)
                       VALUES (:id, :tenant, 'BRAS', :device, 'MIKROTIK', 'CCR2004', '7.20.2', 'HTTPS_REST',
                        'ENSURE_PPPOE_TERMINATION', 'CERTIFIED', :valid, :evidence, :actor, :certified)""",
                ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                    .setParameter("device", certifiedDeviceId).setParameter("valid", now.plusSeconds(3600))
                    .setParameter("evidence", evidenceId).setParameter("actor", UuidV7.generate())
                    .setParameter("certified", now).executeUpdate()
            }
        }
    }

    private fun insertCollector(tenantId: UUID, collectorId: UUID) {
        em.createNativeQuery(
            """INSERT INTO collector
               (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
               VALUES (:id, :tenant, :name, :hash, 'cert', 'ACTIVE', 60)""",
        ).setParameter("id", collectorId).setParameter("tenant", tenantId)
            .setParameter("name", "collector-${UUID.randomUUID()}")
            .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2)).executeUpdate()
    }

    private fun insertCapability(
        tenantId: UUID,
        collectorId: UUID,
        reportId: UUID,
        evidenceId: UUID,
        deviceId: UUID,
        now: Instant,
    ) {
        em.createNativeQuery(
            """INSERT INTO provisioning_collector_device_report
               (id, tenant_id, collector_id, report_key, target_id, vendor, model, firmware, transport,
                capabilities, operation_classes, reported_at, expires_at)
               VALUES (:id, :tenant, :collector, :key, :target, 'MIKROTIK', 'CCR2004', '7.20.2', 'HTTPS_REST',
                'PPPOE_TERMINATION', 'ENSURE_PPPOE_TERMINATION', :observed, :expires)""",
        ).setParameter("id", reportId).setParameter("tenant", tenantId).setParameter("collector", collectorId)
            .setParameter("key", "$deviceId@$now").setParameter("target", deviceId.toString())
            .setParameter("observed", now.minusSeconds(10)).setParameter("expires", now.plusSeconds(300)).executeUpdate()
        em.createNativeQuery(
            """INSERT INTO provisioning_capability_evidence
               (id, tenant_id, collector_id, report_id, device_kind, device_id, vendor, model, firmware,
                transport, operation_class, supported, observed_at, expires_at)
               VALUES (:id, :tenant, :collector, :report, 'BRAS', :device, 'MIKROTIK', 'CCR2004', '7.20.2',
                'HTTPS_REST', 'ENSURE_PPPOE_TERMINATION', true, :observed, :expires)""",
        ).setParameter("id", evidenceId).setParameter("tenant", tenantId).setParameter("collector", collectorId)
            .setParameter("report", reportId).setParameter("device", deviceId)
            .setParameter("observed", now.minusSeconds(10)).setParameter("expires", now.plusSeconds(300)).executeUpdate()
    }

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }
}
