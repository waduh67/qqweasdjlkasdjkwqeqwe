package com.duluin.ftth.hotspot.adapter.inbound.web

import com.duluin.ftth.hotspot.application.service.InvalidPortalContextException
import com.duluin.ftth.hotspot.application.service.PortalContext
import com.duluin.ftth.hotspot.application.service.PortalContextService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/hotspot/public/portal")
class PortalContextController(private val portalContexts: PortalContextService) {
    @GetMapping("/{portalId}/state")
    fun issue(
        @PathVariable portalId: String,
        @RequestParam(required = false) returnPath: String?,
    ): IssuedPortalContextResponse {
        val issued = portalContexts.issue(portalId, returnPath)
        return IssuedPortalContextResponse(issued.state, issued.expiresAt)
    }

    @GetMapping("/{portalId}/context")
    fun context(@PathVariable portalId: String, @RequestParam state: String): PortalContextResponse =
        PortalContextResponse.from(portalContexts.resolve(portalId, state))

    @ExceptionHandler(InvalidPortalContextException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidContext() = mapOf("message" to "Konteks portal tidak valid")
}

data class IssuedPortalContextResponse(val state: String, val expiresAt: Instant)

data class PortalContextResponse(
    val siteName: String,
    val displayName: String?,
    val logoUrl: String?,
    val returnPath: String?,
) {
    companion object {
        fun from(context: PortalContext) = PortalContextResponse(
            siteName = context.siteName,
            displayName = context.displayName,
            logoUrl = context.logoUrl,
            returnPath = context.returnPath,
        )
    }
}
