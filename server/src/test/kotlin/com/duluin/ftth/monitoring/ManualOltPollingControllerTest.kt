package com.duluin.ftth.monitoring

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.security.AccessChecker
import com.duluin.ftth.common.infrastructure.web.GlobalExceptionHandler
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.monitoring.adapter.inbound.web.ManualOltPollingController
import com.duluin.ftth.monitoring.application.port.inbound.ManualOltPollResult
import com.duluin.ftth.monitoring.application.port.inbound.ManualOltPollUseCase
import com.duluin.ftth.monitoring.application.service.OltReadingPersister
import com.duluin.ftth.monitoring.application.service.ServerSideOltPoller
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OltPollingTarget
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.OltAdapter
import com.duluin.ftth.snmp.ProbeResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.slf4j.LoggerFactory
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class ManualOltPollingControllerTest {
    private val tenantId = UUID.randomUUID()
    private val oltId = UUID.randomUUID()
    private val target = OltPollingTarget(
        id = oltId,
        code = "OLT-01",
        vendor = "TEST",
        host = "192.0.2.10",
        snmpCommunity = "test-secret",
        snmpPort = 1161,
        active = true,
        snmpEnabled = true,
    )
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var useCase: ManualOltPollUseCase
    private lateinit var currentUser: TestCurrentUser
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        context = AnnotationConfigApplicationContext(PollingBeans::class.java)
        useCase = context.getBean(ManualOltPollUseCase::class.java)
        currentUser = context.getBean(TestCurrentUser::class.java)
        currentUser.user = user(setOf("monitoring.collector.manage"))
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("operator", null, "ROLE_TEST")
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(ManualOltPollingController::class.java))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        context.close()
    }

    @Test
    fun `authorized POST returns the exact manual poll contract`() {
        val checkedAt = Instant.parse("2026-09-11T03:04:05Z")
        `when`(useCase.pollOlt(oltId)).thenReturn(
            ManualOltPollResult(oltId, "OLT-01", true, 12, null, checkedAt),
        )

        val body = mvc.perform(post("/api/monitoring/olts/$oltId/poll"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val expected = """
            {
              "oltId": "$oltId", "oltCode": "OLT-01", "reachable": true,
              "readingCount": 12, "failureReason": null, "checkedAt": "2026-09-11T03:04:05Z"
            }
        """.trimIndent()
        assertThat(ObjectMapper().readTree(body)).isEqualTo(ObjectMapper().readTree(expected))
        verify(useCase).pollOlt(oltId)
    }

    @Test
    fun `unreachable device remains HTTP 200`() {
        `when`(useCase.pollOlt(oltId)).thenReturn(
            ManualOltPollResult(oltId, "OLT-01", false, 0, "timeout", Instant.parse("2026-09-11T03:04:05Z")),
        )

        mvc.perform(post("/api/monitoring/olts/$oltId/poll"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reachable").value(false))
            .andExpect(jsonPath("$.failureReason").value("timeout"))
    }

    @Test
    fun `collector manage permission is required`() {
        currentUser.user = user(setOf("monitoring.collector.view"))

        mvc.perform(post("/api/monitoring/olts/$oltId/poll"))
            .andExpect(status().isForbidden)

        verifyNoInteractions(useCase)
    }

    @Test
    fun `busy poll maps to conflict`() {
        `when`(useCase.pollOlt(oltId)).thenThrow(ConflictException("Polling OLT sedang berjalan"))

        mvc.perform(post("/api/monitoring/olts/$oltId/poll"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value(409))
    }

    @Test
    fun `missing OLT maps to not found on the manual route`() {
        `when`(useCase.pollOlt(oltId)).thenThrow(NotFoundException("OLT tidak ditemukan"))

        mvc.perform(post("/api/monitoring/olts/$oltId/poll"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail").value("OLT tidak ditemukan"))
    }

    @Test
    fun `invalid polling readiness maps to bad request on the manual route`() {
        `when`(useCase.pollOlt(oltId)).thenThrow(ValidationException("SNMP OLT dinonaktifkan"))

        mvc.perform(post("/api/monitoring/olts/$oltId/poll"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("SNMP OLT dinonaktifkan"))
    }

    @Test
    fun `manual persistence failure returns sanitized 500 without throwable logging`() {
        val sentinel = "persistence-secret password=hunter2 host=10.88.77.66"
        val network = mock(NetworkApi::class.java)
        val persister = mock(OltReadingPersister::class.java)
        `when`(network.findPollingTarget(oltId)).thenReturn(target)
        doThrow(IllegalStateException(sentinel))
            .`when`(persister).persist(tenantId, target, true, emptyList(), null)
        val poller = ServerSideOltPoller(network, AdapterRegistry(listOf(ReachableAdapter())), persister)
        val persistenceFailureMvc = MockMvcBuilders.standaloneSetup(ManualOltPollingController(poller))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

        val captured = captureRootLogs {
            TenantContext.runAs(tenantId) {
                persistenceFailureMvc.perform(post("/api/monitoring/olts/$oltId/poll"))
                    .andExpect(status().isInternalServerError)
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.detail").value("Polling OLT gagal diproses"))
                    .andReturn()
            }
        }

        assertThat(captured.result.response.contentAsString).doesNotContain(sentinel).doesNotContain("reachable")
        assertThat(captured.result.resolvedException?.message).isEqualTo("Polling OLT gagal diproses")
        assertThat(captured.result.resolvedException?.cause).isNull()
        assertThat(captured.renderedText()).contains("Polling manual OLT OLT-01 gagal disimpan").doesNotContain(sentinel)
        assertThat(captured.events.filter { it.loggerName == ServerSideOltPoller::class.java.name })
            .allSatisfy { event -> assertThat(event.throwableProxy).isNull() }
        verify(persister).persist(tenantId, target, true, emptyList(), null)
    }

    private fun user(permissions: Set<String>) = AuthenticatedUser(
        UUID.randomUUID(), UUID.randomUUID(), "operator@example.test", "Operator", false, permissions, emptySet(),
    )

    private fun <T> captureRootLogs(action: () -> T): LogCapture<T> {
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val result = try {
            action()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return LogCapture(result, appender.list.toList())
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(ManualOltPollingController::class, AccessChecker::class)
    class PollingBeans {
        @Bean
        fun manualOltPollUseCase(): ManualOltPollUseCase = mock(ManualOltPollUseCase::class.java)

        @Bean
        fun currentUser(): TestCurrentUser = TestCurrentUser()
    }

    class TestCurrentUser : CurrentUserProvider {
        var user: AuthenticatedUser? = null
        override fun currentOrNull(): AuthenticatedUser? = user
    }

    private class ReachableAdapter : OltAdapter {
        override val vendor: String = "TEST"

        override fun probe(target: OltTarget): ProbeResult = ProbeResult.Reachable("test", 1)

        override fun pollOnus(target: OltTarget): List<OnuReading> = emptyList()
    }

    private data class LogCapture<T>(
        val result: T,
        val events: List<ILoggingEvent>,
    ) {
        fun renderedText(): String = events.joinToString("\n") { event ->
            listOfNotNull(
                event.formattedMessage,
                event.throwableProxy?.let(ThrowableProxyUtil::asString),
            ).joinToString("\n")
        }
    }
}
