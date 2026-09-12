package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePostingPersistence
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.hibernate.jdbc.ReturningWork
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.util.AopTestUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicInteger

enum class TestPostingPhase { DOCUMENT, HEADER, LEGS, BALANCES, RESERVATIONS, CUSTODY, MATERIAL_FACT, FACTS, EVENTS }

internal class PostingJdbcProbe(context: ConfigurableApplicationContext, private val phase: TestPostingPhase,
    private val occurrence: Int = 1, private val omitWrite: Boolean = false, private val action: () -> Unit) : AutoCloseable {
    private val target=AopTestUtils.getUltimateTargetObject<WarehousePostingPersistence>(context.getBean(WarehousePostingPersistence::class.java))
    private val field=WarehousePostingPersistence::class.java.getDeclaredField("entityManager").apply { isAccessible=true }
    private val original=field.get(target) as EntityManager
    private val hits=AtomicInteger()
    val observations: Int get() = hits.get()

    init {
        val manager=proxy(EntityManager::class.java,original) { method,args ->
            val result=invoke(original,method,args)
            if(method.name=="unwrap" && args?.firstOrNull()==Session::class.java) session(result as Session) else result
        }
        field.set(target,manager)
    }

    private fun session(session: Session): Session = proxy(Session::class.java,session) { method,args ->
        if(method.name=="doReturningWork") {
            val work=args!![0] as ReturningWork<*>
            session.doReturningWork(ReturningWork { connection -> work.execute(connection(connection)) })
        } else invoke(session,method,args)
    }

    private fun connection(connection: Connection): Connection = proxy(Connection::class.java,connection) { method,args ->
        val result=invoke(connection,method,args)
        if(method.name=="prepareStatement" && args?.firstOrNull() is String) statement(result as PreparedStatement,args[0] as String) else result
    }

    private fun statement(statement: PreparedStatement,sql: String): PreparedStatement = proxy(PreparedStatement::class.java,statement) { method,args ->
        if (omitWrite && method.name=="executeUpdate" && matches(sql)) {
            hits.incrementAndGet()
            action()
            1
        } else {
            val result=invoke(statement,method,args)
            if(method.name=="executeUpdate" && matches(sql) && hits.incrementAndGet()==occurrence) action()
            result
        }
    }

    private fun matches(sql: String): Boolean {
        val normalized=sql.trim().lowercase().replace(Regex("\\s+")," ")
        return when(phase) {
            TestPostingPhase.DOCUMENT -> normalized.startsWith("update inventory_document set")
            TestPostingPhase.HEADER -> normalized.startsWith("insert into inventory_movement(")
            TestPostingPhase.LEGS -> normalized.startsWith("insert into inventory_movement_leg(")
            TestPostingPhase.BALANCES -> normalized.startsWith("update inventory_balance_projection set")
            TestPostingPhase.RESERVATIONS -> normalized.startsWith("update inventory_reservation set") || normalized.startsWith("insert into inventory_reservation(")
            TestPostingPhase.CUSTODY -> normalized.startsWith("update inventory_serialized_asset set")
            TestPostingPhase.MATERIAL_FACT -> normalized.startsWith("insert into inventory_customer_material_fact(")
            TestPostingPhase.FACTS -> normalized.startsWith("insert into inventory_usage_snapshot(")
            TestPostingPhase.EVENTS -> normalized.startsWith("insert into inventory_outbox(")
        }
    }

    override fun close() { field.set(target,original) }

    private fun invoke(target: Any,method: Method,args: Array<out Any?>?): Any? = try {
        method.invoke(target,*(args ?: emptyArray()))
    } catch(failure: InvocationTargetException) { throw failure.targetException }

    private fun <T> proxy(type: Class<T>,target: T,action: (Method,Array<out Any?>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _,method,args ->
            if(method.declaringClass==Any::class.java) invoke(target as Any,method,args) else action(method,args)
        })
}
