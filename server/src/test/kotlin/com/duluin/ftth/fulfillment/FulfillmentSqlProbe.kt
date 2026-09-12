package com.duluin.ftth.fulfillment

import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.util.AopTestUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

internal enum class FulfillmentSqlPhase { SNAPSHOT, COMPLETED_EFFECT }

internal class FulfillmentSqlProbe(context: ConfigurableApplicationContext, phase: FulfillmentSqlPhase,
    action: () -> Unit) : AutoCloseable {
    private val type = when (phase) {
        FulfillmentSqlPhase.SNAPSHOT -> FulfillmentApprovalStore::class.java
        FulfillmentSqlPhase.COMPLETED_EFFECT -> FulfillmentCheckpointPersistenceAdapter::class.java
    }
    private val target = AopTestUtils.getUltimateTargetObject<Any>(context.getBean(type))
    private val field = type.getDeclaredField("entityManager").apply { isAccessible = true }
    private val original = EntityManager::class.java.cast(field.get(target))
    private val fired = AtomicBoolean()

    init {
        val manager = Proxy.newProxyInstance(EntityManager::class.java.classLoader, arrayOf(EntityManager::class.java)) { _, method, arguments ->
            val result = invoke(original, method, arguments)
            val sql = arguments?.firstOrNull()?.toString().orEmpty()
            val matches = when (phase) {
                FulfillmentSqlPhase.SNAPSHOT -> sql.startsWith("INSERT INTO fulfillment_approval_snapshot")
                FulfillmentSqlPhase.COMPLETED_EFFECT -> sql.startsWith("UPDATE fulfillment_effect_progress p SET status = 'COMPLETED'")
            }
            if (method.name == "createNativeQuery" && matches) {
                val query = Query::class.java.cast(result)
                Proxy.newProxyInstance(Query::class.java.classLoader, arrayOf(Query::class.java)) { proxy, operation, values ->
                    val value = invoke(query, operation, values)
                    if (operation.name == "executeUpdate" && fired.compareAndSet(false, true)) action()
                    if (value === query) proxy else value
                }
            } else result
        }
        field.set(target, manager)
    }

    override fun close() { field.set(target, original) }

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (failure: InvocationTargetException) { throw failure.targetException }
}
