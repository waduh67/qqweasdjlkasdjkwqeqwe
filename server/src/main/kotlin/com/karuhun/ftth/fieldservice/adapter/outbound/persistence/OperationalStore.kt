package com.karuhun.ftth.fieldservice.adapter.outbound.persistence

import com.karuhun.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.karuhun.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

@Repository
class OperationalStore(private val jdbc: TenantTransactionJdbc) {
    fun <T> use(action: (OperationalSql) -> T): T {
        var result: Result<T>? = null
        jdbc.withinTenant(TenantContext.tenantId()) { result = Result.success(action(OperationalSql(it, TenantContext.tenantId()))) }
        return requireNotNull(result).getOrThrow()
    }
}

class OperationalSql(private val connection: Connection, val tenant: UUID) {
    fun lock() { query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "field-settings:$tenant") { true } }
    fun update(sql: String, vararg args: Any?): Int = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }
    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
    }
}
