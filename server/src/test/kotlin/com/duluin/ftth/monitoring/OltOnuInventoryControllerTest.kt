package com.duluin.ftth.monitoring

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.security.AccessChecker
import com.duluin.ftth.common.infrastructure.web.GlobalExceptionHandler
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.monitoring.adapter.inbound.web.OltOnuInventoryController
import com.duluin.ftth.monitoring.application.port.inbound.OltOnuInventory
import com.duluin.ftth.monitoring.application.port.inbound.OltOnuInventoryRow
import com.duluin.ftth.monitoring.application.port.inbound.OltOnuInventoryUseCase
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class OltOnuInventoryControllerTest {
    private val oltId = UUID.randomUUID()
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var useCase: OltOnuInventoryUseCase
    private lateinit var currentUser: TestCurrentUser
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        context = AnnotationConfigApplicationContext(InventoryBeans::class.java)
        useCase = context.getBean(OltOnuInventoryUseCase::class.java)
        currentUser = context.getBean(TestCurrentUser::class.java)
        currentUser.user = user(setOf("network.olt.view", "monitoring.provisioning.view"))
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("operator", null, "ROLE_TEST")
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(OltOnuInventoryController::class.java))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        context.close()
    }

    @Test
    fun `both read permissions return the exact inventory contract including nullable fields`() {
        `when`(useCase.read(oltId)).thenReturn(
            OltOnuInventory(
                oltId, "OLT-01", "HSGQ", null, Instant.parse("2026-09-08T02:10:11Z"),
                listOf(OltOnuInventoryRow(
                    index = "16777472", ontId = "PON01/0", name = null, serialNumber = "TEST001122AA",
                    state = "Active", runningState = "ONLINE", configState = "Normal", deviceType = null,
                    rxPowerDbm = -22.0, lastUpTime = "2026-09-08 09:10:11", lastDownTime = null, lastDownCause = null,
                )),
                listOf("DEVICE_TYPE belum dipetakan"),
            ),
        )

        val body = mvc.perform(get("/api/monitoring/olts/$oltId/onus"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val expected = """
            {
              "oltId": "$oltId", "oltCode": "OLT-01", "vendor": "HSGQ",
              "systemDescription": null, "readAt": "2026-09-08T02:10:11Z",
              "onus": [{
                "index": "16777472", "ontId": "PON01/0", "name": null, "serialNumber": "TEST001122AA",
                "state": "Active", "runningState": "ONLINE", "configState": "Normal", "deviceType": null,
                "rxPowerDbm": -22.0, "lastUpTime": "2026-09-08 09:10:11",
                "lastDownTime": null, "lastDownCause": null
              }],
              "warnings": ["DEVICE_TYPE belum dipetakan"]
            }
        """.trimIndent()
        val mapper = ObjectMapper()
        assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree(expected))
        verify(useCase).read(oltId)
        verifyNoMoreInteractions(useCase)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "network.olt.view", "monitoring.provisioning.view", "monitoring.collector.manage"])
    fun `missing either read permission blocks service access`(permission: String) {
        currentUser.user = user(setOf(permission))

        mvc.perform(get("/api/monitoring/olts/$oltId/onus"))
            .andExpect(status().isForbidden)

        verifyNoInteractions(useCase)
    }

    @Test
    fun `missing current user cannot read the inventory`() {
        currentUser.user = null

        mvc.perform(get("/api/monitoring/olts/$oltId/onus"))
            .andExpect(status().isForbidden)

        verifyNoInteractions(useCase)
    }

    @Test
    fun `tenant missing OLT is a not found problem`() {
        `when`(useCase.read(oltId)).thenThrow(NotFoundException("OLT tidak ditemukan"))

        mvc.perform(get("/api/monitoring/olts/$oltId/onus"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
    }

    @Test
    fun `missing device configuration is a bad request problem`() {
        `when`(useCase.read(oltId)).thenThrow(ValidationException("Community string SNMP belum diisi"))

        mvc.perform(get("/api/monitoring/olts/$oltId/onus"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `essential read failure is a bad gateway problem not successful empty inventory`() {
        `when`(useCase.read(oltId)).thenThrow(SnmpProbeFailure("Pembacaan SERIAL gagal"))

        mvc.perform(get("/api/monitoring/olts/$oltId/onus"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.status").value(502))
            .andExpect(jsonPath("$.detail").value("Pembacaan SERIAL gagal"))
            .andExpect(jsonPath("$.onus").doesNotExist())
    }

    @Test
    fun `route only accepts an OLT identifier not an arbitrary host`() {
        mvc.perform(get("/api/monitoring/olts/192.0.2.10/onus"))
            .andExpect(status().isBadRequest)

        verifyNoInteractions(useCase)
    }

    private fun user(permissions: Set<String>) = AuthenticatedUser(
        UUID.randomUUID(), UUID.randomUUID(), "operator@example.test", "Operator", false, permissions, emptySet(),
    )

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(OltOnuInventoryController::class, AccessChecker::class)
    class InventoryBeans {
        @Bean
        fun inventoryUseCase(): OltOnuInventoryUseCase = mock(OltOnuInventoryUseCase::class.java)

        @Bean
        fun currentUser(): TestCurrentUser = TestCurrentUser()
    }

    class TestCurrentUser : CurrentUserProvider {
        var user: AuthenticatedUser? = null
        override fun currentOrNull(): AuthenticatedUser? = user
    }
}
