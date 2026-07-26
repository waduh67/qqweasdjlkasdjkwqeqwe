package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.monitoring.application.port.inbound.AutoProvisionPolicyView
import com.duluin.ftth.monitoring.application.port.inbound.ManageAutoProvisionPolicyUseCase
import com.duluin.ftth.monitoring.application.port.outbound.AutoProvisionPolicyRepository
import com.duluin.ftth.monitoring.domain.model.AutoProvisionPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Sisi operator dari kebijakan auto-provisioning zero-touch. Membaca setelan
 * tenant (atau bawaan mati bila belum pernah disetel) dan mengubahnya.
 *
 * Perubahan dicatat ke jejak audit: menyalakan zero-touch memberi wewenang sistem
 * memutasi data pelanggan tanpa review, jadi harus jelas siapa & kapan menyalakan.
 */
@Service
@Transactional(readOnly = true)
class AutoProvisionPolicyService(
    private val repository: AutoProvisionPolicyRepository,
    private val auditor: AuditRecorder,
) : ManageAutoProvisionPolicyUseCase {

    override fun get(): AutoProvisionPolicyView =
        (repository.find() ?: AutoProvisionPolicy.defaultFor(TenantContext.tenantId())).toView()

    @Transactional
    override fun setEnabled(enabled: Boolean): AutoProvisionPolicyView {
        val policy = repository.find() ?: AutoProvisionPolicy.defaultFor(TenantContext.tenantId())
        policy.update(enabled)
        val saved = repository.save(policy)
        auditor.record(
            action = if (enabled) "monitoring.auto_provision.enabled" else "monitoring.auto_provision.disabled",
            entityType = "AutoProvisionPolicy",
            entityId = saved.id,
            tenantId = saved.tenantId,
        )
        return saved.toView()
    }

    private fun AutoProvisionPolicy.toView() = AutoProvisionPolicyView(enabled = enabled)
}
