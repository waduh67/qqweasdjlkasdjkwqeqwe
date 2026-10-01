package com.duluin.ftth.cpe.adapter.inbound.web

import com.duluin.ftth.cpe.application.port.outbound.AcsConnectionProbe
import com.duluin.ftth.cpe.application.port.outbound.AcsProbe
import com.duluin.ftth.cpe.application.service.AcsSettingsService
import com.duluin.ftth.cpe.application.service.UpdateAcsSettings
import com.duluin.ftth.cpe.domain.model.AcsConnectionSettings
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/platform/acs-settings")
class PlatformAcsSettingsController(private val settings: AcsSettingsService, private val probe: AcsConnectionProbe) {
    @GetMapping
    @PreAuthorize("@authz.can('platform.acs.view')")
    fun get(): PlatformAcsSettingsView = settings.current().view()

    @PutMapping
    @PreAuthorize("@authz.can('platform.acs.manage')")
    fun update(@Valid @RequestBody request: PlatformAcsSettingsRequest): PlatformAcsSettingsView =
        settings.update(UpdateAcsSettings(request.nbiUrl, request.username, request.password, request.cwmpUrl)).view()

    @PostMapping("/test")
    @PreAuthorize("@authz.can('platform.acs.manage')")
    fun test(): AcsProbe = probe.test(settings.current())

    private fun AcsConnectionSettings.view() = PlatformAcsSettingsView(version, nbiUrl, username, !password.isNullOrBlank(), cwmpUrl, persisted)
}

data class PlatformAcsSettingsView(val version: UUID, val nbiUrl: String, val username: String, val passwordSet: Boolean, val cwmpUrl: String?, val persisted: Boolean)
data class PlatformAcsSettingsRequest(
    @field:NotBlank @field:Size(max = 2048) val nbiUrl: String,
    @field:Size(max = 255) val username: String? = null,
    @field:Size(max = 512) val password: String? = null,
    @field:NotBlank @field:Size(max = 2048) val cwmpUrl: String,
)
