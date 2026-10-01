package com.duluin.ftth.cpe

import com.duluin.ftth.common.infrastructure.security.AccessChecker
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.ReadOnlyLockGuard
import com.duluin.ftth.cpe.adapter.inbound.web.PlatformAcsSettingsController
import com.duluin.ftth.cpe.adapter.inbound.web.PlatformAcsSettingsRequest
import com.duluin.ftth.cpe.application.port.outbound.AcsConnectionProbe
import com.duluin.ftth.cpe.application.port.outbound.AcsProbe
import com.duluin.ftth.cpe.application.service.AcsSettingsService
import com.duluin.ftth.cpe.application.service.UpdateAcsSettings
import com.duluin.ftth.cpe.domain.model.AcsConnectionSettings
import com.duluin.ftth.iam.domain.catalog.PermissionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class PlatformAcsSettingsSecurityTest {
    @Configuration
    @EnableMethodSecurity
    class Security

    @Test
    fun `controller enforces view and manage independently and platform admin can use both`() {
        val settings = AcsConnectionSettings(AcsConnectionSettings.ENV_VERSION, "http://api.test", "", null, "http://cwmp.test:7547", false)
        val service = Mockito.mock(AcsSettingsService::class.java)
        Mockito.`when`(service.current()).thenReturn(settings)
        Mockito.`when`(service.update(UpdateAcsSettings("https://api.test", "", "", "http://cwmp.test:7547"))).thenReturn(settings)
        var user: AuthenticatedUser? = null
        val currentUser = object : CurrentUserProvider { override fun currentOrNull() = user }
        AnnotationConfigApplicationContext().use { context ->
            context.register(Security::class.java)
            context.registerBean("authz", AccessChecker::class.java, java.util.function.Supplier { AccessChecker(currentUser, context.getBeanProvider(ReadOnlyLockGuard::class.java)) })
            context.registerBean(PlatformAcsSettingsController::class.java, java.util.function.Supplier { PlatformAcsSettingsController(service, AcsConnectionProbe { AcsProbe(true, 1, null) }) })
            context.refresh()
            val controller = context.getBean(PlatformAcsSettingsController::class.java)
            val request = PlatformAcsSettingsRequest("https://api.test", "", "", "http://cwmp.test:7547")
            fun login(permissions: Set<String>, admin: Boolean = false) {
                user = AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "qa@example.test", "QA", admin, permissions, emptySet())
                SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(user, "", emptyList())
            }
            try {
                login(emptySet())
                assertThatThrownBy { controller.get() }.isInstanceOf(AccessDeniedException::class.java)
                assertThatThrownBy { controller.update(request) }.isInstanceOf(AccessDeniedException::class.java)
                assertThatThrownBy { controller.test() }.isInstanceOf(AccessDeniedException::class.java)
                Mockito.verifyNoInteractions(service)
                login(setOf("platform.acs.view"))
                assertThat(controller.get().nbiUrl).isEqualTo(settings.nbiUrl)
                assertThatThrownBy { controller.update(request) }.isInstanceOf(AccessDeniedException::class.java)
                assertThatThrownBy { controller.test() }.isInstanceOf(AccessDeniedException::class.java)
                login(setOf("platform.acs.manage"))
                assertThatThrownBy { controller.get() }.isInstanceOf(AccessDeniedException::class.java)
                assertThat(controller.update(request).passwordSet).isFalse()
                assertThat(controller.test().reachable).isTrue()
                login(emptySet(), admin = true)
                controller.get()
                controller.update(request)
                controller.test()
            } finally { SecurityContextHolder.clearContext() }
        }
        assertThat(PermissionCatalog.ALL.filter { it.code.value.startsWith("platform.acs.") }).hasSize(2).allMatch { it.platformOnly }
    }
}
