package com.karuhun.ftth.inventory.adapter.outbound.persistence

import com.karuhun.ftth.common.infrastructure.persistence.isDraftExpired as commonIsDraftExpired

typealias WarehouseCommandJdbc = com.karuhun.ftth.common.infrastructure.persistence.WarehouseCommandJdbc

internal fun java.sql.SQLException.isDraftExpired(): Boolean = commonIsDraftExpired()
