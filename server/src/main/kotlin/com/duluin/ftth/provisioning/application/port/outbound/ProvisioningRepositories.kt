package com.duluin.ftth.provisioning.application.port.outbound

import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.DriftRecord
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.SegmentProfile
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.VlanPool
import java.util.UUID

interface SegmentProfileRepository { fun save(value: SegmentProfile): SegmentProfile; fun findById(id: UUID): SegmentProfile? }
interface VlanPoolRepository {
    fun save(value: VlanPool): VlanPool
    fun findById(id: UUID): VlanPool?
    fun findByIdForUpdate(id: UUID): VlanPool?
}
interface ServiceIntentRepository { fun save(value: ServiceIntent): ServiceIntent; fun findById(id: UUID): ServiceIntent? }
interface ProvisionPlanRepository { fun save(value: ProvisionPlan): ProvisionPlan; fun findById(id: UUID): ProvisionPlan? }
interface ProvisionExecutionRepository {
    fun save(value: ProvisionExecution): ProvisionExecution
    fun findById(id: UUID): ProvisionExecution?
    fun findByIdempotencyKey(key: String): ProvisionExecution?
}
interface DeviceSnapshotRepository { fun save(value: DeviceSnapshot): DeviceSnapshot; fun findById(id: UUID): DeviceSnapshot? }
interface DeviceObservationRepository { fun save(value: DeviceObservation): DeviceObservation; fun findById(id: UUID): DeviceObservation? }
interface DriftRecordRepository { fun save(value: DriftRecord): DriftRecord; fun findById(id: UUID): DriftRecord? }
interface AdapterCertificationRepository {
    fun save(value: AdapterCertification): AdapterCertification
    fun findById(id: UUID): AdapterCertification?
}
