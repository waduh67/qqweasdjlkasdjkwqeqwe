package com.duluin.ftth.hotspot

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.VoucherSessionRef
import com.duluin.ftth.hotspot.adapter.inbound.web.VoucherController
import com.duluin.ftth.hotspot.adapter.inbound.web.VoucherSessionResponse
import com.duluin.ftth.hotspot.application.port.inbound.ManageVoucherUseCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID

class HotspotVoucherSessionControllerTest {
    @Test
    fun `projects safe BNG voucher session and returns 404 when absent`() {
        val nasId = UUID.randomUUID()
        val startedAt = Instant.parse("2026-08-29T10:00:00Z")
        val controller = VoucherController(
            unusedVoucherUseCase(),
            bngApi { externalId ->
                if (externalId == "voucher-1") {
                    VoucherSessionRef(
                        externalId, true, nasId, "192.0.2.10", "raw-radius-session-id",
                        "AA-BB-CC-DD-EE-FF", startedAt, startedAt, 123L, 456L,
                    )
                } else null
            },
        )

        assertThat(controller.getSession("voucher-1")).isEqualTo(
            VoucherSessionResponse("voucher-1", true, nasId, "192.0.2.10", startedAt, startedAt, 123L, 456L),
        )
        assertThatThrownBy { controller.getSession("unknown") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Suppress("UNCHECKED_CAST")
    private fun unusedVoucherUseCase(): ManageVoucherUseCase = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ManageVoucherUseCase::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException("Unexpected ${method.name} call") } as ManageVoucherUseCase

    @Suppress("UNCHECKED_CAST")
    private fun bngApi(findVoucherSession: (String) -> VoucherSessionRef?): BngApi = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(BngApi::class.java),
    ) { _, method, args ->
        when (method.name) {
            "findVoucherSession" -> findVoucherSession(args!![0] as String)
            else -> throw UnsupportedOperationException("Unexpected ${method.name} call")
        }
    } as BngApi
}
