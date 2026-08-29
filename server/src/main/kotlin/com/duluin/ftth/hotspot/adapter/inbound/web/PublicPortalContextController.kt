package com.duluin.ftth.hotspot.adapter.inbound.web

import com.duluin.ftth.common.infrastructure.security.AttemptThrottle
import com.duluin.ftth.hotspot.application.port.inbound.InvalidPortalContextException
import com.duluin.ftth.hotspot.application.port.inbound.IssuePortalContextCommand
import com.duluin.ftth.hotspot.application.port.inbound.ResolvePublicPortalContextUseCase
import com.duluin.ftth.hotspot.application.service.PublicPortalContextService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/public/hotspot/portal-context")
class PublicPortalContextController(
    private val contexts: ResolvePublicPortalContextUseCase,
    private val throttle: AttemptThrottle,
    private val contextService: PublicPortalContextService,
) {
    @PostMapping("/issue")
    fun issue(@Valid @RequestBody request: IssueRequest, servletRequest: HttpServletRequest): IssuedResponse =
        try {
            throttle.spendHotspotPortalContext(servletRequest.remoteAddr)
            contexts.issue(request.toCommand()).let { IssuedResponse(it.state, it.expiresAt.toString()) }
        } catch (ex: InvalidPortalContextException) {
            contextService.auditFailure(request.portalId)
            throw ex
        }

    @PostMapping("/resolve")
    fun resolve(@Valid @RequestBody request: ResolveRequest, servletRequest: HttpServletRequest): ResolvedResponse =
        try {
            throttle.spendHotspotPortalContext(servletRequest.remoteAddr)
            contexts.resolve(request.state).let {
                ResolvedResponse(it.displayName, it.logoUrl, it.redirectUrl, it.clientMac, it.clientIp)
            }
        } catch (ex: InvalidPortalContextException) {
            throw ex
        }

    @ExceptionHandler(InvalidPortalContextException::class)
    fun invalidContext(): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Konteks portal tidak valid")

    data class IssueRequest(
        @field:NotBlank @field:Size(max = 22) val portalId: String,
        @field:Size(max = 64) val clientMac: String? = null,
        @field:Size(max = 64) val clientIp: String? = null,
        @field:Size(max = 2_000) val originalUrl: String? = null,
    ) {
        fun toCommand() = IssuePortalContextCommand(portalId, clientMac, clientIp, originalUrl)
    }

    data class ResolveRequest(@field:NotBlank @field:Size(max = 4_096) val state: String)
    data class IssuedResponse(val state: String, val expiresAt: String)
    data class ResolvedResponse(
        val displayName: String,
        val logoUrl: String?,
        val redirectUrl: String?,
        val clientMac: String?,
        val clientIp: String?,
    )
}
