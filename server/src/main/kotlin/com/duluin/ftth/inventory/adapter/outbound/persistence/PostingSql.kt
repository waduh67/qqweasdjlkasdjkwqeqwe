package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class PostingSql(val connection: Connection, val tenant: UUID) {
    fun update(sql: String, vararg parameters: Any?): Int = connection.prepareStatement(sql).use { statement ->
        parameters.forEachIndexed { index, value -> statement.setObject(index+1, when(value) { is Enum<*> -> value.name; is Instant -> Timestamp.from(value); else -> value }) }
        statement.executeUpdate()
    }
    fun <T> query(sql: String, vararg parameters: Any?, map: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        parameters.forEachIndexed { index, value -> statement.setObject(index+1, when(value) { is Enum<*> -> value.name; is Instant -> Timestamp.from(value); else -> value }) }
        statement.executeQuery().use { rows -> buildList { while(rows.next()) add(map(rows)) } }
    }
    fun value(sql: String, vararg parameters: Any?): String? = query(sql,*parameters) { it.getString(1) }.singleOrNull()
    fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code,code.name))
}

internal fun ResultSet.uuid(column: String): UUID = getObject(column,UUID::class.java)
internal fun ResultSet.optionalUuid(column: String): UUID? = getObject(column,UUID::class.java)
