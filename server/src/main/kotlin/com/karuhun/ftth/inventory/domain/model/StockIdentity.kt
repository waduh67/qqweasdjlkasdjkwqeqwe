package com.karuhun.ftth.inventory.domain.model

import com.karuhun.ftth.common.domain.identity.MacIdentity
import com.karuhun.ftth.common.domain.identity.SerialIdentity

data class StockIdentity private constructor(val serial: SerialIdentity, val mac: MacIdentity?) {
    val quantity: StockQuantity get() = StockQuantity.of(1L, StockUnit.EA)

    companion object {
        fun parse(serial: String, mac: String?): StockIdentity =
            StockIdentity(SerialIdentity.parse(serial), mac?.let(MacIdentity::parse))
    }
}
