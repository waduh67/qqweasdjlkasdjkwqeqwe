package com.karuhun.ftth.monitoring

import com.karuhun.ftth.common.infrastructure.security.AccessChecker
import com.karuhun.ftth.common.infrastructure.web.GlobalExceptionHandler
import com.karuhun.ftth.common.security.AuthenticatedUser
import com.karuhun.ftth.common.security.CurrentUserProvider
import com.karuhun.ftth.monitoring.adapter.inbound.web.PonPortDiscoveryController
import com.karuhun.ftth.monitoring.application.port.inbound.DiscoverPonPortsUseCase
import com.karuhun.ftth.monitoring.application.port.inbound.PonPortDiscovery
import com.karuhun.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import com.karuhun.ftth.network.adapter.inbound.web.OltController
import com.karuhun.ftth.network.application.port.inbound.ManageOltUseCase
import com.karuhun.ftth.network.application.port.inbound.PonPortImportResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.context.annotation.*
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class PonPortDiscoveryControllerTest {
    private val oltId = UUID.randomUUID()
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var discovery: DiscoverPonPortsUseCase
    private lateinit var inventory: ManageOltUseCase
    private lateinit var currentUser: TestCurrentUser
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        context = AnnotationConfigApplicationContext(Beans::class.java)
        discovery = context.getBean(DiscoverPonPortsUseCase::class.java)
        inventory = context.getBean(ManageOltUseCase::class.java)
        currentUser = context.getBean(TestCurrentUser::class.java)
        permissions("network.olt.view", "monitoring.provisioning.view")
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("operator", null, "ROLE_TEST")
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(PonPortDiscoveryController::class.java), context.getBean(OltController::class.java))
            .setControllerAdvice(GlobalExceptionHandler()).build()
    }

    @AfterEach
    fun cleanup() { SecurityContextHolder.clearContext(); context.close() }

    @ParameterizedTest
    @ValueSource(strings = ["", "network.olt.view", "monitoring.provisioning.view", "network.olt.update"])
    fun `discovery needs both read permissions`(permission: String) {
        permissions(permission)
        mvc.perform(get("/api/monitoring/olts/$oltId/pon-ports")).andExpect(status().isForbidden)
        verifyNoInteractions(discovery, inventory)
    }

    @Test
    fun `read permissions allow discovery without inventory writes`() {
        `when`(discovery.discover(oltId)).thenReturn(PonPortDiscovery(oltId, Instant.now(), emptyList(), emptyList()))
        mvc.perform(get("/api/monitoring/olts/$oltId/pon-ports")).andExpect(status().isOk)
        verify(discovery).discover(oltId)
        verifyNoInteractions(inventory)
    }

    @Test
    fun `upstream failure is not a successful empty port list`() {
        `when`(discovery.discover(oltId)).thenThrow(SnmpProbeFailure("OLT tidak dapat dihubungi."))
        mvc.perform(get("/api/monitoring/olts/$oltId/pon-ports")).andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.ports").doesNotExist())
    }

    @Test
    fun `read-only user cannot import ports`() {
        mvc.perform(post("/api/olts/$oltId/pon-ports/import").contentType(MediaType.APPLICATION_JSON).content("""{"labels":["PON1"]}"""))
            .andExpect(status().isForbidden)
        verifyNoInteractions(inventory)
    }

    @Test
    fun `update permission can import and empty requests are rejected`() {
        permissions("network.olt.update")
        `when`(inventory.importPonPorts(oltId, listOf("PON1"))).thenReturn(PonPortImportResult(1, 0))
        mvc.perform(post("/api/olts/$oltId/pon-ports/import").contentType(MediaType.APPLICATION_JSON).content("""{"labels":["PON1"]}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.created").value(1))
        mvc.perform(post("/api/olts/$oltId/pon-ports/import").contentType(MediaType.APPLICATION_JSON).content("""{"labels":[]}"""))
            .andExpect(status().isBadRequest)
        verify(inventory).importPonPorts(oltId, listOf("PON1"))
        verifyNoMoreInteractions(inventory)
    }

    private fun permissions(vararg permissions: String) {
        currentUser.user = AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "operator@example.test", "Operator", false, permissions.toSet(), emptySet())
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(PonPortDiscoveryController::class, OltController::class, AccessChecker::class)
    class Beans {
        @Bean fun discovery(): DiscoverPonPortsUseCase = mock(DiscoverPonPortsUseCase::class.java)
        @Bean fun inventory(): ManageOltUseCase = mock(ManageOltUseCase::class.java)
        @Bean fun currentUser() = TestCurrentUser()
    }

    class TestCurrentUser : CurrentUserProvider {
        var user: AuthenticatedUser? = null
        override fun currentOrNull() = user
    }
}
