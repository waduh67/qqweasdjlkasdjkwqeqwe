package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.InventoryMaterialUsageService
import com.duluin.ftth.inventory.application.service.InventorySettlementService
import jakarta.persistence.EntityManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Test-only failure after the real owner posted stock and facts, before the caller commits. */
class WarehouseLifecycleFailurePort {
    val rejectUsage = AtomicBoolean(false)
    val reached = AtomicBoolean(false)
    val tamperDeployment = AtomicReference<String?>()
    val lastFailure = AtomicReference<String?>()

    fun afterUsage() {
        if (rejectUsage.get()) {
            check(TransactionSynchronizationManager.isActualTransactionActive())
            reached.set(true)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.STALE_REVISION, "Injected precommit interruption"))
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class WarehouseLifecycleFailureConfiguration {
    @Bean fun lifecycleFailurePort() = WarehouseLifecycleFailurePort()
    @Bean @Primary fun failingUsageOwner(delegate: InventoryMaterialUsageService, probe: WarehouseLifecycleFailurePort): InventoryMaterialUsageApi =
        object : InventoryMaterialUsageApi {
            override fun reportUse(context: MaterialPlanningContext, request: MaterialUsageRequest,
                metadata: WarehouseMutationMetadata): WarehouseOperationReceipt = delegate.reportUse(context, request, metadata).also { probe.afterUsage() }
            override fun usage(id: UUID): String = delegate.usage(id)
        }
    @Bean @Primary fun tamperedSettlementOwner(delegate: InventorySettlementService, probe: WarehouseLifecycleFailurePort,
        entityManager: EntityManager): InventorySettlementApi = object : InventorySettlementApi {
        override fun freeze(context: MaterialPlanningContext): MaterialSettlementSource {
            val actual = delegate.freeze(context)
            val mode = probe.tamperDeployment.get() ?: return actual
            // Run the actual DB witness constraint at the first snapshot insert, before delivery.
            entityManager.createNativeQuery("SET CONSTRAINTS warehouse_fulfillment_deployments IMMEDIATE").executeUpdate()
            return actual.copy(deployments = when (mode) {
                "OMITTED" -> emptyList()
                "FOREIGN_ASSET" -> actual.deployments.map { it.copy(assetId = UUID.randomUUID()) }
                else -> error("Unknown witness fault")
            })
        }
        override fun verify(context: MaterialPlanningContext, approval: MaterialSettlementApproval): MaterialVerificationReceipt =
            delegate.verify(context, approval)
    }
    @Bean fun lifecycleDiagnostics(probe: WarehouseLifecycleFailurePort) = object : org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        override fun extendHandlerExceptionResolvers(resolvers: MutableList<org.springframework.web.servlet.HandlerExceptionResolver>) {
            resolvers.add(0, org.springframework.web.servlet.HandlerExceptionResolver { _, _, _, exception ->
                probe.lastFailure.set(generateSequence<Throwable>(exception) { it.cause }.last().message)
                null
            })
        }
    }
}
