package com.duluin.ftth.monitoring

import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.adapter.inbound.web.WarehouseHttpErrors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler

class WarehouseDiscoveryITDeferredConflict {
    @Test
    fun `deferred warehouse rejection remains a typed conflict without private SQL details`() {
        val handler = WarehouseHttpErrors::class.java.methods.singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(DataIntegrityViolationException::class.java)) &&
                it.getAnnotation(ExceptionHandler::class.java) != null
        }
        assertThat(handler).isNotNull()
        val response = requireNotNull(handler).invoke(WarehouseHttpErrors(), DataIntegrityViolationException("private SQL reference")) as ResponseEntity<*>
        assertThat(response.statusCode.value()).isEqualTo(409)
        assertThat(response.body).isEqualTo(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "SOURCE_NOT_VERIFIED"))
    }
}
