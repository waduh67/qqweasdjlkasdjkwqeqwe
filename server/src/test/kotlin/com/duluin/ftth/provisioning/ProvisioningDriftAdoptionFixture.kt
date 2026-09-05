package com.duluin.ftth.provisioning

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.ProvisioningResultState
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.contract.ProvisioningVerificationObservation
import com.duluin.ftth.monitoring.application.service.CollectorProvisioningExchange
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedStateHash
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.util.UUID

internal class ProvisioningDriftAdoptionFixture(
    private val entityManager: EntityManager,
    private val provisioningExchange: CollectorProvisioningExchange,
) {
    fun insert(tenantId: UUID, certificationOperation: String = "ENSURE_TAGGED_VLAN"): DriftFixture {
        val ids = FixtureIds()
        val now = Instant.now()
        sql("INSERT INTO provisioning_vlan_pool (id,tenant_id,name,vlan_start,vlan_end) VALUES (:a,:t,'adopt',100,200)", ids.poolId, tenantId)
        entityManager.createNativeQuery("INSERT INTO provisioning_segment_profile (id,tenant_id,name,pool_id) VALUES (:a,:t,'adopt',:pool)")
            .setParameter("a", ids.profileId).setParameter("t", tenantId).setParameter("pool", ids.poolId).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_service_intent
            (id,tenant_id,subscription_id,segment_profile_id,encapsulation,status)
            VALUES (:a,:t,:subscription,:profile,'SINGLE_TAG','DRAFT')""")
            .setParameter("a", ids.intentId).setParameter("t", tenantId).setParameter("subscription", UUID.randomUUID())
            .setParameter("profile", ids.profileId).executeUpdate()
        entityManager.createNativeQuery("INSERT INTO provisioning_plan (id,tenant_id,intent_id,revision,status,content_hash) VALUES (:a,:t,:intent,1,'GENERATED',:hash)")
            .setParameter("a", ids.planId).setParameter("t", tenantId).setParameter("intent", ids.intentId)
            .setParameter("hash", "0".repeat(64)).executeUpdate()
        insertStep(tenantId, ids)
        insertStates(tenantId, ids, now)
        insertCertificationEvidence(
            CertificationFixture(tenantId, ids.deviceId, ids.collectorId, ids.reportId, ids.capabilityId, ids.observationId, now, certificationOperation),
        )
        return DriftFixture(ids.driftId, ids.deviceId, ids.collectorId)
    }

    fun completeObservation(fixture: DriftFixture): Instant {
        val target = ProvisioningTarget(fixture.deviceId.toString(), "ROUTER", "router.invalid", "HTTPS_REST")
        val dispatch = provisioningExchange.exchange(
            fixture.collectorId, TenantContext.tenantId(), CollectorHeartbeat("task14"), listOf(target),
        ).commands.single { it.observationOnly }
        val liveState = ProvisioningResultState(managedResourceCount = 1, vlanIds = listOf(120))
        val observedAt = Instant.now()
        val result = ProvisioningStepResult(
            dispatch.planId, dispatch.revision, dispatch.stepId, dispatch.attemptId, dispatch.target.deviceId,
            dispatch.operationClass, dispatch.idempotencyKey, 0, ProvisioningCommandPhase.PREFLIGHT, true, observedAt,
            preflight = com.duluin.ftth.contract.ProvisioningPreflightSnapshot(
                observedAt, dispatch.expectedPreconditionHash ?: "0".repeat(64), liveState,
            ),
            verification = ProvisioningVerificationObservation(observedAt, false, liveState.observationHash(), liveState),
        )
        val acknowledgement = provisioningExchange.exchange(
            fixture.collectorId, TenantContext.tenantId(), CollectorHeartbeat("task14", provisioningResults = listOf(result)),
            listOf(target),
        ).acknowledgement
        assertThat(acknowledgement.resultAttemptIds).containsExactly(dispatch.attemptId)
        return observedAt
    }

    fun rejectMismatchedObservation(fixture: DriftFixture) {
        val target = ProvisioningTarget(fixture.deviceId.toString(), "ROUTER", "router.invalid", "HTTPS_REST")
        val dispatch = provisioningExchange.exchange(
            fixture.collectorId, TenantContext.tenantId(), CollectorHeartbeat("task14"), listOf(target),
        ).commands.single { it.observationOnly }
        val liveState = ProvisioningResultState(managedResourceCount = 1, vlanIds = listOf(120))
        val validTime = Instant.now()
        val base = ProvisioningStepResult(
            dispatch.planId, dispatch.revision, dispatch.stepId, dispatch.attemptId, dispatch.target.deviceId,
            dispatch.operationClass, dispatch.idempotencyKey, 1, ProvisioningCommandPhase.PREFLIGHT, true, validTime,
            preflight = com.duluin.ftth.contract.ProvisioningPreflightSnapshot(
                validTime, dispatch.expectedPreconditionHash ?: "0".repeat(64), liveState,
            ),
            verification = ProvisioningVerificationObservation(validTime, false, liveState.observationHash(), liveState),
        )
        val lateTime = dispatch.deadline.plusSeconds(1)
        val late = base.copy(
            fencingEpoch = 0,
            completedAt = lateTime,
            verification = ProvisioningVerificationObservation(lateTime, false, liveState.observationHash(), liveState),
        )
        val wrongHash = base.copy(
            fencingEpoch = 0,
            verification = ProvisioningVerificationObservation(validTime, false, "f".repeat(64), liveState),
        )
        val acknowledgement = provisioningExchange.exchange(
            fixture.collectorId, TenantContext.tenantId(),
            CollectorHeartbeat("task14", provisioningResults = listOf(base, late, wrongHash)), listOf(target),
        ).acknowledgement
        assertThat(acknowledgement.resultAttemptIds).isEmpty()
    }

    fun completeUnavailableObservation(fixture: DriftFixture) {
        val target = ProvisioningTarget(fixture.deviceId.toString(), "ROUTER", "router.invalid", "HTTPS_REST")
        val dispatch = provisioningExchange.exchange(
            fixture.collectorId, TenantContext.tenantId(), CollectorHeartbeat("task14"), listOf(target),
        ).commands.single { it.observationOnly }
        val result = ProvisioningStepResult(
            dispatch.planId, dispatch.revision, dispatch.stepId, dispatch.attemptId, dispatch.target.deviceId,
            dispatch.operationClass, dispatch.idempotencyKey, 0, ProvisioningCommandPhase.PREFLIGHT, false, Instant.now(),
            errorCode = com.duluin.ftth.contract.ProvisioningErrorCode.TIMEOUT,
        )
        val acknowledgement = provisioningExchange.exchange(
            fixture.collectorId, TenantContext.tenantId(), CollectorHeartbeat("task14", provisioningResults = listOf(result)),
            listOf(target),
        ).acknowledgement
        assertThat(acknowledgement.resultAttemptIds).containsExactly(dispatch.attemptId)
    }

    fun count(table: String, column: String, id: UUID): Long =
        (entityManager.createNativeQuery("SELECT count(*) FROM $table WHERE $column = :id").setParameter("id", id).singleResult as Number).toLong()

    fun countWhere(table: String, idColumn: String, id: UUID, valueColumn: String, value: String): Long =
        (entityManager.createNativeQuery("SELECT count(*) FROM $table WHERE $idColumn = :id AND $valueColumn = :value")
            .setParameter("id", id).setParameter("value", value).singleResult as Number).toLong()

    fun latestObservationAt(deviceId: UUID): Instant = entityManager.createNativeQuery(
        "SELECT observed_at FROM provisioning_device_observation WHERE device_id = :device ORDER BY observed_at DESC LIMIT 1",
    ).setParameter("device", deviceId).singleResult as Instant

    private fun insertStep(tenantId: UUID, ids: FixtureIds) {
        entityManager.createNativeQuery("""INSERT INTO provisioning_step
            (id,tenant_id,plan_id,step_order,device_kind,device_id,operation)
            VALUES (:a,:t,:plan,1,'ROUTER',:device,'ENSURE_TAGGED_VLAN')""")
            .setParameter("a", ids.stepId).setParameter("t", tenantId).setParameter("plan", ids.planId)
            .setParameter("device", ids.deviceId).executeUpdate()
        listOf(
            "safety.vendor" to "MIKROTIK", "safety.model" to "CCR2004",
            "safety.firmware" to "7.20.2", "safety.transport" to "HTTPS_REST",
        ).forEach { (key, value) ->
            entityManager.createNativeQuery("""INSERT INTO provisioning_step_attribute
                (id,tenant_id,step_id,attribute_key,attribute_value) VALUES (:a,:t,:step,:key,:value)""")
                .setParameter("a", UUID.randomUUID()).setParameter("t", tenantId).setParameter("step", ids.stepId)
                .setParameter("key", key).setParameter("value", value).executeUpdate()
        }
    }

    private fun insertStates(tenantId: UUID, ids: FixtureIds, now: Instant) {
        entityManager.createNativeQuery("""INSERT INTO provisioning_device_snapshot
            (id,tenant_id,device_kind,device_id,plan_id,normalized_state,captured_at)
            VALUES (:a,:t,'ROUTER',:device,:plan,CAST(:state AS jsonb),:now)""")
            .setParameter("a", ids.snapshotId).setParameter("t", tenantId).setParameter("device", ids.deviceId)
            .setParameter("plan", ids.planId).setParameter("state", BASELINE).setParameter("now", now).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_device_observation
            (id,tenant_id,device_kind,device_id,normalized_state,observed_at)
            VALUES (:a,:t,'ROUTER',:device,CAST(:state AS jsonb),:now)""")
            .setParameter("a", ids.observationId).setParameter("t", tenantId).setParameter("device", ids.deviceId)
            .setParameter("state", OBSERVED).setParameter("now", now).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_drift_record
            (id,tenant_id,device_kind,device_id,snapshot_id,observation_id,status,recorded_at)
            VALUES (:a,:t,'ROUTER',:device,:snapshot,:observation,'BENIGN',:now)""")
            .setParameter("a", ids.driftId).setParameter("t", tenantId).setParameter("device", ids.deviceId)
            .setParameter("snapshot", ids.snapshotId).setParameter("observation", ids.observationId).setParameter("now", now).executeUpdate()
        val state = NormalizedDeviceState.of(NormalizedField.VLANS to NormalizedValue.sequence(NormalizedValue.number(110)))
        val readbackHash = NormalizedStateHash.sha256(state)
        entityManager.createNativeQuery("""INSERT INTO provisioning_execution
            (id,tenant_id,intent_id,plan_id,idempotency_key,status) VALUES (:a,:t,:intent,:plan,:key,'QUEUED')""")
            .setParameter("a", ids.executionId).setParameter("t", tenantId).setParameter("intent", ids.intentId)
            .setParameter("plan", ids.planId).setParameter("key", "readback-${ids.executionId}").executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_execution_step
            (id,tenant_id,execution_id,plan_step_id,step_order,device_kind,device_id,status,after_hash)
            VALUES (:a,:t,:execution,:step,1,'ROUTER',:device,'VERIFIED',:hash)""")
            .setParameter("a", ids.executionStepId).setParameter("t", tenantId).setParameter("execution", ids.executionId)
            .setParameter("step", ids.stepId).setParameter("device", ids.deviceId).setParameter("hash", readbackHash).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_step_snapshot
            (id,tenant_id,execution_step_id,snapshot_kind,state_hash,normalized_state,captured_at)
            VALUES (:a,:t,:step,'AFTER',:hash,CAST(:state AS jsonb),:now)""")
            .setParameter("a", UUID.randomUUID()).setParameter("t", tenantId).setParameter("step", ids.executionStepId)
            .setParameter("hash", readbackHash).setParameter("state", BASELINE).setParameter("now", now).executeUpdate()
    }

    private fun insertCertificationEvidence(fixture: CertificationFixture) {
        val (tenantId, deviceId, collectorId, reportId, capabilityId, observationId, now, operation) = fixture
        entityManager.createNativeQuery("""INSERT INTO collector
            (id,tenant_id,name,api_key_hash,api_key_hint,status,poll_interval_seconds)
            VALUES (:a,:t,:name,:hash,'task14','ACTIVE',60)""")
            .setParameter("a", collectorId).setParameter("t", tenantId).setParameter("name", "adoption-$collectorId")
            .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2)).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_collector_device_report
            (id,tenant_id,collector_id,report_key,target_id,vendor,model,firmware,transport,capabilities,
             operation_classes,reported_at,expires_at)
            VALUES (:a,:t,:collector,:key,:target,'MIKROTIK','CCR2004','7.20.2','HTTPS_REST','VLAN',
                    :operation,:now,:expires)""")
            .setParameter("a", reportId).setParameter("t", tenantId).setParameter("collector", collectorId)
            .setParameter("key", "$deviceId@$now").setParameter("target", deviceId.toString())
            .setParameter("operation", operation).setParameter("now", now).setParameter("expires", now.plusSeconds(600)).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_capability_evidence
            (id,tenant_id,collector_id,report_id,device_kind,device_id,vendor,model,firmware,transport,
             operation_class,supported,observed_at,expires_at)
            VALUES (:a,:t,:collector,:report,'ROUTER',:device,'MIKROTIK','CCR2004','7.20.2','HTTPS_REST',
                    :operation,true,:now,:expires)""")
            .setParameter("a", capabilityId).setParameter("t", tenantId).setParameter("collector", collectorId)
            .setParameter("report", reportId).setParameter("device", deviceId).setParameter("now", now)
            .setParameter("operation", operation).setParameter("expires", now.plusSeconds(600)).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_adapter_certification
            (id,tenant_id,device_kind,device_id,vendor,model,firmware,transport,operation_class,status,
             valid_until,evidence_id,certified_by,certified_at)
            VALUES (:a,:t,'ROUTER',:device,'MIKROTIK','CCR2004','7.20.2','HTTPS_REST',:operation,
                    'CERTIFIED',:expires,:evidence,:actor,:now)""")
            .setParameter("a", UUID.randomUUID()).setParameter("t", tenantId).setParameter("device", deviceId)
            .setParameter("expires", now.plusSeconds(600)).setParameter("evidence", capabilityId)
            .setParameter("operation", operation).setParameter("actor", UUID.randomUUID()).setParameter("now", now).executeUpdate()
        entityManager.createNativeQuery("""INSERT INTO provisioning_management_safety_evidence
            (id,tenant_id,device_kind,device_id,protected_vlan_ranges,protected_ip_prefixes,protected_vrfs,
             protected_interface_roles,protected_collector_paths,protected_oob_routes,available_oob_routes,
             observed_at,valid_until,complete,source_type,device_observation_source_id)
            VALUES (:a,:t,'ROUTER',:device,'','','','','','','',:now,:expires,true,'DEVICE_OBSERVATION',:source)""")
            .setParameter("a", UUID.randomUUID()).setParameter("t", tenantId).setParameter("device", deviceId)
            .setParameter("now", now).setParameter("expires", now.plusSeconds(600))
            .setParameter("source", observationId).executeUpdate()
    }

    private fun sql(statement: String, id: UUID, tenantId: UUID) = entityManager.createNativeQuery(statement)
        .setParameter("a", id).setParameter("t", tenantId).executeUpdate()

    private data class FixtureIds(
        val poolId: UUID = UUID.randomUUID(),
        val profileId: UUID = UUID.randomUUID(),
        val intentId: UUID = UUID.randomUUID(),
        val planId: UUID = UUID.randomUUID(),
        val stepId: UUID = UUID.randomUUID(),
        val executionId: UUID = UUID.randomUUID(),
        val executionStepId: UUID = UUID.randomUUID(),
        val deviceId: UUID = UUID.randomUUID(),
        val snapshotId: UUID = UUID.randomUUID(),
        val observationId: UUID = UUID.randomUUID(),
        val driftId: UUID = UUID.randomUUID(),
        val capabilityId: UUID = UUID.randomUUID(),
        val collectorId: UUID = UUID.randomUUID(),
        val reportId: UUID = UUID.randomUUID(),
    )

    private data class CertificationFixture(
        val tenantId: UUID,
        val deviceId: UUID,
        val collectorId: UUID,
        val reportId: UUID,
        val capabilityId: UUID,
        val observationId: UUID,
        val now: Instant,
        val operation: String,
    )

    private companion object {
        const val BASELINE = "{\"vlans\":[110]}"
        const val OBSERVED = "{\"vlans\":[110],\"external\":true}"
    }
}

internal data class DriftFixture(val driftId: UUID, val deviceId: UUID, val collectorId: UUID)
