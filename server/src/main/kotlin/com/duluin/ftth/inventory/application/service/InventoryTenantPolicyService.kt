package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.InventoryTenantCutoverApi
import com.duluin.ftth.inventory.InventoryTenantInitializationApi
import com.duluin.ftth.inventory.TenantCutoverChangeFence
import com.duluin.ftth.inventory.TenantCutoverFence
import com.duluin.ftth.inventory.TenantCutoverSnapshot
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseCutoverState
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehouseOperationClass
import com.duluin.ftth.inventory.application.port.outbound.InventoryTenantPolicyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

enum class CutoverTransitionUnavailable { INDEPENDENT_APPROVAL_NOT_INSTALLED }

@Service
class InventoryTenantPolicyService(private val repository: InventoryTenantPolicyRepository) : InventoryTenantCutoverApi, InventoryTenantInitializationApi {
    @Transactional(readOnly = true)
    override fun read(): TenantCutoverSnapshot = repository.read() ?: missing()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockForCommand(expectedEpoch: Long, operation: WarehouseOperationClass): TenantCutoverFence {
        val snapshot = locked(expectedEpoch, false)
        if (!allows(snapshot.state, operation)) fail(WarehouseErrorCode.CUTOVER_REQUIRED, "Operasi memerlukan cutover gudang yang sesuai")
        if (operation in approvalOperations) fail(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED, "Persetujuan migrasi belum tersedia")
        return Fence(snapshot)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockForTransition(expectedEpoch: Long): TenantCutoverChangeFence = Fence(locked(expectedEpoch, true))

    @Transactional(propagation = Propagation.MANDATORY)
    override fun initializeNewEmptyTenant(): TenantCutoverSnapshot = repository.initialize(newEmptyTenant = true)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun initializeExistingTenant(): TenantCutoverSnapshot = repository.initialize(newEmptyTenant = false)

    @Transactional(propagation = Propagation.MANDATORY)
    fun beginValidation(expectedEpoch: Long): TenantCutoverSnapshot {
        val snapshot = locked(expectedEpoch, true)
        if (snapshot.state != WarehouseCutoverState.LEGACY) fail(WarehouseErrorCode.CUTOVER_REQUIRED, "Validasi hanya dimulai dari LEGACY")
        return repository.beginValidation(expectedEpoch)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun finalizeValidation(expectedEpoch: Long): CutoverTransitionUnavailable {
        locked(expectedEpoch, true)
        return CutoverTransitionUnavailable.INDEPENDENT_APPROVAL_NOT_INSTALLED
    }

    fun allows(state: WarehouseCutoverState, operation: WarehouseOperationClass): Boolean = when (state) {
        WarehouseCutoverState.LEGACY -> operation in controlOperations
        WarehouseCutoverState.VALIDATING -> operation in controlOperations || operation in approvalOperations
        WarehouseCutoverState.ENFORCED -> operation in controlOperations || operation in setOf(
            WarehouseOperationClass.ORDINARY_STOCK, WarehouseOperationClass.ASSET_ASSIGNMENT,
        )
    }

    private fun locked(expectedEpoch: Long, exclusive: Boolean): TenantCutoverSnapshot {
        val snapshot = repository.lock(exclusive) ?: missing()
        if (snapshot.epoch != expectedEpoch) fail(WarehouseErrorCode.STALE_CUTOVER, "Epoch cutover telah berubah")
        return snapshot
    }

    private fun missing(): Nothing = fail(WarehouseErrorCode.CUTOVER_REQUIRED, "Kebijakan cutover tenant belum diinisialisasi")
    private fun fail(code: WarehouseErrorCode, message: String): Nothing = throw WarehouseContractException(WarehouseError(code, message))

    private class Fence(override val snapshot: TenantCutoverSnapshot) : TenantCutoverFence, TenantCutoverChangeFence {
        private val transactionResources = TransactionSynchronizationManager.getResourceMap().toMap()
        private val thread = Thread.currentThread()
        private var active = true

        init {
            check(TransactionSynchronizationManager.isActualTransactionActive())
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) { active = false }
            })
        }

        override fun assertHeld() {
            check(active && Thread.currentThread() === thread && TenantContext.tenantId() == snapshot.tenantId &&
                TransactionSynchronizationManager.isActualTransactionActive() &&
                transactionResources.all { (key, value) -> TransactionSynchronizationManager.getResource(key) === value }) {
                "Cutover fence is no longer held in its originating transaction"
            }
        }
    }

    private val controlOperations = setOf(WarehouseOperationClass.CONTROL_PLANE, WarehouseOperationClass.MIGRATION_REPORT)
    private val approvalOperations = setOf(
        WarehouseOperationClass.PROVENANCE_RESOLUTION, WarehouseOperationClass.MIGRATION_APPROVAL,
        WarehouseOperationClass.MIGRATION_BASELINE, WarehouseOperationClass.CUTOVER_FINALIZATION,
    )
}
