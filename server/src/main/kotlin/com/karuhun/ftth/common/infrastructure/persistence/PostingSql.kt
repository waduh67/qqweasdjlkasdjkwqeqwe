package com.karuhun.ftth.common.infrastructure.persistence

import com.karuhun.ftth.common.warehouse.WarehouseContractException
import com.karuhun.ftth.common.warehouse.WarehouseError
import com.karuhun.ftth.common.warehouse.WarehouseErrorCode
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class PostingSql(val connection: Connection, val tenant: UUID) {
    fun update(sql: String, vararg parameters: Any?): Int = connection.prepareStatement(sql).use { statement ->
        parameters.forEachIndexed { index, value -> statement.setObject(index+1, when(value) { is Enum<*> -> value.name; is Instant -> Timestamp.from(value); else -> value }) }
        statement.executeUpdate()
    }
    fun <T> query(sql: String, vararg parameters: Any?, map: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        parameters.forEachIndexed { index, value -> statement.setObject(index+1, when(value) { is Enum<*> -> value.name; is Instant -> Timestamp.from(value); else -> value }) }
        statement.executeQuery().use { rows -> buildList { while(rows.next()) add(map(rows)) } }
    }
    fun value(sql: String, vararg parameters: Any?): String? = query(sql,*parameters) { it.getString(1) }.singleOrNull()
    fun fail(code: WarehouseErrorCode, cause: Throwable? = null): Nothing =
        throw WarehouseContractException(WarehouseError(code,code.name)).apply { if (cause != null) initCause(cause) }
}

fun ResultSet.uuid(column: String): UUID = getObject(column,UUID::class.java)
fun ResultSet.optionalUuid(column: String): UUID? = getObject(column,UUID::class.java)
