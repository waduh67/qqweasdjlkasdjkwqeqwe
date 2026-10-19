package com.karuhun.ftth.inventory.adapter.outbound.persistence

import java.sql.ResultSet
import java.util.UUID

internal typealias PostingSql = com.karuhun.ftth.common.infrastructure.persistence.PostingSql
internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
internal fun ResultSet.optionalUuid(column: String): UUID? = getObject(column, UUID::class.java)
