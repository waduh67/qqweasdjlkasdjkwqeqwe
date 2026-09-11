package com.duluin.ftth.monitoring

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.OnuRef
import com.duluin.ftth.monitoring.application.service.AlarmEngine
import com.duluin.ftth.monitoring.application.service.DiscoveredOnuRecorder
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import com.duluin.ftth.monitoring.application.service.OltReadingPersister
import com.duluin.ftth.monitoring.application.service.ServerSideOltPoller
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRepository
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRuleRepository
import com.duluin.ftth.monitoring.application.port.outbound.IngestBatchRepository
import com.duluin.ftth.monitoring.application.port.outbound.OnuMetricRepository
import com.duluin.ftth.monitoring.domain.model.Alarm
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OltPollingTarget
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.OltAdapter
import com.duluin.ftth.snmp.ProbeResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ManualOltPollerTest {
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

    @Test
    fun `manual poll returns readings and uses the scheduled persistence path`() {
        val readings = listOf(reading("TEST001122AA"), reading("TEST001122BB"))
        val adapter = RecordingAdapter(readings = readings)
        val persister = mock(OltReadingPersister::class.java)
        val poller = poller(target, adapter, persister)
        val before = Instant.now()

        val result = TenantContext.runAs(tenantId) { poller.pollOlt(oltId) }

        assertThat(result.oltId).isEqualTo(oltId)
        assertThat(result.oltCode).isEqualTo("OLT-01")
        assertThat(result.reachable).isTrue()
        assertThat(result.readingCount).isEqualTo(2)
        assertThat(result.failureReason).isNull()
        assertThat(result.checkedAt).isBetween(before, Instant.now())
        verify(persister).persist(tenantId, target, true, readings, null)
        assertThat(adapter.probeCalls).isEqualTo(1)
        assertThat(adapter.pollCalls).isEqualTo(1)
    }

    @Test
    fun `unreachable probe reason is sanitized in result logs and persisted alarm`() {
        val sentinel = "probe-secret community=private host=10.23.45.67"
        val adapter = RecordingAdapter(probeResult = ProbeResult.Unreachable(sentinel))
        val fixture = persistedPoller(adapter)

        val captured = captureLogs(ServerSideOltPoller::class.java) {
            TenantContext.runAs(tenantId) { fixture.poller.pollOlt(oltId) }
        }
        val alarm = fixture.savedAlarm()

        assertThat(captured.result.reachable).isFalse()
        assertThat(captured.result.readingCount).isZero()
        assertThat(captured.result.failureReason).isEqualTo("OLT tidak dapat dijangkau")
        assertThat(captured.result.failureReason).doesNotContain(sentinel)
        assertThat(captured.renderedText()).doesNotContain(sentinel)
        assertThat(alarm.message).contains("OLT tidak dapat dijangkau").doesNotContain(sentinel)
        assertThat(adapter.pollCalls).isZero()
    }

    @Test
    fun `thrown adapter failure is sanitized in result logs and persisted alarm`() {
        val sentinel = "exception-secret community=private host=10.98.76.54"
        val adapter = RecordingAdapter(probeFailure = IllegalStateException(sentinel))
        val fixture = persistedPoller(adapter)

        val captured = captureLogs(ServerSideOltPoller::class.java) {
            TenantContext.runAs(tenantId) { fixture.poller.pollOlt(oltId) }
        }
        val alarm = fixture.savedAlarm()

        assertThat(captured.result.reachable).isFalse()
        assertThat(captured.result.readingCount).isZero()
        assertThat(captured.result.failureReason).isEqualTo("Polling SNMP gagal")
        assertThat(captured.result.failureReason).doesNotContain(sentinel)
        assertThat(captured.renderedText()).contains("Polling OLT OLT-01 gagal").doesNotContain(sentinel)
        assertThat(captured.events).allSatisfy { event -> assertThat(event.throwableProxy).isNull() }
        assertThat(alarm.message).contains("Polling SNMP gagal").doesNotContain(sentinel)
        assertThat(adapter.pollCalls).isZero()
    }

    @Test
    fun `thrown ONU walk failure is sanitized in result logs and persisted alarm`() {
        val sentinel = "walk-secret community=private host=10.77.66.55"
        val adapter = RecordingAdapter(pollFailure = IllegalStateException(sentinel))
        val fixture = persistedPoller(adapter)

        val captured = captureLogs(ServerSideOltPoller::class.java) {
            TenantContext.runAs(tenantId) { fixture.poller.pollOlt(oltId) }
        }
        val alarm = fixture.savedAlarm()

        assertThat(captured.result.reachable).isFalse()
        assertThat(captured.result.readingCount).isZero()
        assertThat(captured.result.failureReason).isEqualTo("Polling SNMP gagal")
        assertThat(captured.result.failureReason).doesNotContain(sentinel)
        assertThat(captured.renderedText()).contains("Polling OLT OLT-01 gagal").doesNotContain(sentinel)
        assertThat(captured.events).allSatisfy { event -> assertThat(event.throwableProxy).isNull() }
        assertThat(alarm.message).contains("Polling SNMP gagal").doesNotContain(sentinel)
        assertThat(adapter.probeCalls).isEqualTo(1)
        assertThat(adapter.pollCalls).isEqualTo(1)
    }

    @Test
    fun `manual persistence failure is classified safely and releases single flight`() {
        val sentinel = "persistence-secret password=hunter2 host=10.88.77.66"
        val adapter = RecordingAdapter()
        val persister = mock(OltReadingPersister::class.java)
        doThrow(IllegalStateException(sentinel))
            .doNothing()
            .`when`(persister).persist(tenantId, target, true, emptyList(), null)
        val poller = poller(target, adapter, persister)

        val captured = captureLogs(ServerSideOltPoller::class.java) {
            catchThrowable { TenantContext.runAs(tenantId) { poller.pollOlt(oltId) } }
        }

        assertThat(captured.result.message).isEqualTo("Polling OLT gagal diproses")
        assertThat(captured.result.message).doesNotContain(sentinel)
        assertThat(captured.result.cause).isNull()
        assertThat(captured.renderedText()).contains("Polling manual OLT OLT-01 gagal disimpan").doesNotContain(sentinel)
        assertThat(captured.events).allSatisfy { event -> assertThat(event.throwableProxy).isNull() }

        val retry = TenantContext.runAs(tenantId) { poller.pollOlt(oltId) }

        assertThat(retry.reachable).isTrue()
        assertThat(adapter.probeCalls).isEqualTo(2)
        verify(persister, times(2)).persist(tenantId, target, true, emptyList(), null)
    }

    @Test
    fun `missing OLT is rejected before network access`() {
        val network = mock(NetworkApi::class.java)
        val adapter = RecordingAdapter()
        val persister = mock(OltReadingPersister::class.java)
        val poller = ServerSideOltPoller(network, AdapterRegistry(listOf(adapter)), persister)
        `when`(network.findPollingTarget(oltId)).thenReturn(null)

        assertThatThrownBy { TenantContext.runAs(tenantId) { poller.pollOlt(oltId) } }
            .isInstanceOf(NotFoundException::class.java)

        assertThat(adapter.probeCalls).isZero()
        verifyNoInteractions(persister)
    }

    @Test
    fun `configuration failures are rejected before network and persistence`() {
        val invalidTargets = listOf(
            target.copy(active = false),
            target.copy(snmpEnabled = false),
            target.copy(vendor = "UNSUPPORTED"),
            target.copy(host = " "),
            target.copy(snmpCommunity = " "),
        )

        invalidTargets.forEach { invalid ->
            val adapter = RecordingAdapter()
            val persister = mock(OltReadingPersister::class.java)
            val poller = poller(invalid, adapter, persister)

            assertThatThrownBy { TenantContext.runAs(tenantId) { poller.pollOlt(oltId) } }
                .describedAs("target $invalid")
                .isInstanceOf(ValidationException::class.java)

            assertThat(adapter.probeCalls).isZero()
            verifyNoInteractions(persister)
        }
    }

    @Test
    fun `scheduler skips invalid configuration without creating unreachable persistence`() {
        val disabled = target.copy(snmpEnabled = false)
        val network = mock(NetworkApi::class.java)
        val adapter = RecordingAdapter()
        val persister = mock(OltReadingPersister::class.java)
        `when`(network.listAllOltIds()).thenReturn(setOf(oltId))
        `when`(network.findPollingTargets(setOf(oltId))).thenReturn(listOf(disabled))
        val poller = ServerSideOltPoller(network, AdapterRegistry(listOf(adapter)), persister)

        poller.pollTenant(tenantId)

        assertThat(adapter.probeCalls).isZero()
        verifyNoInteractions(persister)
    }

    @Test
    fun `scheduler continues with later OLT when one persistence write fails`() {
        val sentinel = "persistence-secret jdbc:postgresql://internal-db/ftth"
        val laterTarget = target.copy(id = UUID.randomUUID(), code = "OLT-02")
        val network = mock(NetworkApi::class.java)
        val adapter = RecordingAdapter()
        val persister = mock(OltReadingPersister::class.java)
        val ids = setOf(target.id, laterTarget.id)
        `when`(network.listAllOltIds()).thenReturn(ids)
        `when`(network.findPollingTargets(ids)).thenReturn(listOf(target, laterTarget))
        doThrow(IllegalStateException(sentinel))
            .`when`(persister).persist(tenantId, target, true, emptyList(), null)
        val poller = ServerSideOltPoller(network, AdapterRegistry(listOf(adapter)), persister)

        val captured = captureLogs(ServerSideOltPoller::class.java) { poller.pollTenant(tenantId) }

        assertThat(adapter.probeCalls).isEqualTo(2)
        verify(persister).persist(tenantId, laterTarget, true, emptyList(), null)
        assertThat(captured.renderedText()).doesNotContain(sentinel)
        assertThat(captured.events).allSatisfy { event -> assertThat(event.throwableProxy).isNull() }
    }

    @Test
    fun `manual call reports conflict while scheduler owns the same tenant OLT flight`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val adapter = RecordingAdapter(entered = entered, release = release)
        val persister = mock(OltReadingPersister::class.java)
        val network = mock(NetworkApi::class.java)
        `when`(network.listAllOltIds()).thenReturn(setOf(oltId))
        `when`(network.findPollingTargets(setOf(oltId))).thenReturn(listOf(target))
        `when`(network.findPollingTarget(oltId)).thenReturn(target)
        val poller = ServerSideOltPoller(network, AdapterRegistry(listOf(adapter)), persister)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val scheduled = executor.submit { TenantContext.runAs(tenantId) { poller.pollTenant(tenantId) } }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()

            assertThatThrownBy { TenantContext.runAs(tenantId) { poller.pollOlt(oltId) } }
                .isInstanceOf(ConflictException::class.java)
                .hasMessageContaining("sedang")

            release.countDown()
            scheduled.get(5, TimeUnit.SECONDS)
            assertThat(adapter.probeCalls).isEqualTo(1)
            verify(persister).persist(tenantId, target, true, emptyList(), null)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `single flight key includes tenant as well as OLT`() {
        val otherTenant = UUID.randomUUID()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val adapter = FirstCallBlockingAdapter(entered, release)
        val persister = mock(OltReadingPersister::class.java)
        val network = mock(NetworkApi::class.java)
        `when`(network.findPollingTarget(oltId)).thenReturn(target)
        val poller = ServerSideOltPoller(network, AdapterRegistry(listOf(adapter)), persister)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val first = executor.submit { TenantContext.runAs(tenantId) { poller.pollOlt(oltId) } }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()

            val second = TenantContext.runAs(otherTenant) { poller.pollOlt(oltId) }
            assertThat(second.reachable).isTrue()

            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertThat(adapter.probeCalls).isEqualTo(2)
            verify(persister).persist(otherTenant, target, true, emptyList(), null)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `persister evaluates reachability ingests readings and publishes alarm change`() {
        val ingestion = mock(MetricIngestionService::class.java)
        val alarms = mock(AlarmEngine::class.java)
        val events = mutableListOf<Any>()
        val readings = listOf(reading("TEST001122AA"))
        val persister = OltReadingPersister(ingestion, alarms, ApplicationEventPublisher(events::add))

        persister.persist(tenantId, target, reachable = true, readings = readings, failureReason = null)

        verify(ingestion).ingestReadings(tenantId, readings)
        val alarmCall = mockingDetails(alarms).invocations.single { it.method.name == "evaluate" }
        assertThat(alarmCall.arguments.take(6)).containsExactly(
            tenantId, AlarmKind.OLT_UNREACHABLE, oltId, "OLT-01", false, null,
        )
        assertThat(events).containsExactly(AlarmsChangedEvent(tenantId))
    }

    @Test
    fun `persister publishes one alarm change when ONU ingestion also evaluates alarms`() {
        val metricRepository = mock(OnuMetricRepository::class.java)
        val batchRepository = mock(IngestBatchRepository::class.java)
        val customerApi = mock(CustomerApi::class.java)
        val networkApi = mock(NetworkApi::class.java)
        val alarms = mock(AlarmEngine::class.java)
        val discoveredOnus = mock(DiscoveredOnuRecorder::class.java)
        val events = mutableListOf<Any>()
        val publisher = ApplicationEventPublisher(events::add)
        val knownOnu = OnuRef(
            id = UUID.randomUUID(),
            serialNumber = "TEST001122AA",
            customerId = UUID.randomUUID(),
            customerName = "Pelanggan Test",
            odpId = null,
            status = "ONLINE",
        )
        `when`(customerApi.findOnusBySerialNumbers(setOf(knownOnu.serialNumber))).thenReturn(listOf(knownOnu))
        val ingestion = MetricIngestionService(
            metricRepository,
            batchRepository,
            customerApi,
            networkApi,
            alarms,
            discoveredOnus,
            publisher,
        )
        val persister = OltReadingPersister(ingestion, alarms, publisher)

        persister.persist(tenantId, target, reachable = true, readings = listOf(reading(knownOnu.serialNumber)), failureReason = null)

        assertThat(events).containsExactly(AlarmsChangedEvent(tenantId))
    }

    private fun poller(
        selectedTarget: OltPollingTarget,
        adapter: OltAdapter,
        persister: OltReadingPersister,
    ): ServerSideOltPoller {
        val network = mock(NetworkApi::class.java)
        `when`(network.findPollingTarget(oltId)).thenReturn(selectedTarget)
        val adapters = if (selectedTarget.vendor == adapter.vendor) listOf(adapter) else emptyList()
        return ServerSideOltPoller(network, AdapterRegistry(adapters), persister)
    }

    private fun persistedPoller(adapter: OltAdapter): PersistedPollFixture {
        val alarmRepository = mock(AlarmRepository::class.java)
        val alarmEngine = AlarmEngine(alarmRepository, mock(AlarmRuleRepository::class.java))
        val persister = OltReadingPersister(
            mock(MetricIngestionService::class.java),
            alarmEngine,
            ApplicationEventPublisher { },
        )
        return PersistedPollFixture(poller(target, adapter, persister), alarmRepository)
    }

    private fun <T> captureLogs(loggerType: Class<*>, action: () -> T): LogCapture<T> {
        val logger = LoggerFactory.getLogger(loggerType) as Logger
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

    private fun reading(serial: String) = OnuReading(
        oltCode = "OLT-01",
        ponPortLabel = "1/1/1",
        serialNumber = serial,
        status = OnuOperationalStatus.ONLINE,
        rxPowerDbm = -21.5,
        txPowerDbm = null,
        uptimeSeconds = 60,
        distanceMeters = 100,
        observedAt = Instant.parse("2026-09-11T00:00:00Z"),
    )

    private open class RecordingAdapter(
        private val probeResult: ProbeResult = ProbeResult.Reachable("test", 1),
        private val probeFailure: RuntimeException? = null,
        private val pollFailure: RuntimeException? = null,
        private val readings: List<OnuReading> = emptyList(),
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
    ) : OltAdapter {
        override val vendor: String = "TEST"
        var probeCalls = 0
        var pollCalls = 0

        override fun probe(target: OltTarget): ProbeResult {
            probeCalls++
            entered?.countDown()
            release?.await(5, TimeUnit.SECONDS)
            probeFailure?.let { throw it }
            return probeResult
        }

        override fun pollOnus(target: OltTarget): List<OnuReading> {
            pollCalls++
            pollFailure?.let { throw it }
            return readings
        }
    }

    private class FirstCallBlockingAdapter(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : OltAdapter {
        override val vendor: String = "TEST"
        @Volatile var probeCalls = 0

        override fun probe(target: OltTarget): ProbeResult {
            val call = synchronized(this) { ++probeCalls }
            if (call == 1) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            return ProbeResult.Reachable("test", 1)
        }

        override fun pollOnus(target: OltTarget): List<OnuReading> = emptyList()
    }

    private data class PersistedPollFixture(
        val poller: ServerSideOltPoller,
        val alarmRepository: AlarmRepository,
    ) {
        fun savedAlarm(): Alarm = mockingDetails(alarmRepository).invocations
            .single { it.method.name == "save" }
            .arguments
            .single() as Alarm
    }

    private data class LogCapture<T>(
        val result: T,
        val events: List<ILoggingEvent>,
    ) {
        fun renderedText(): String = events.joinToString("\n") { event ->
            listOfNotNull(event.formattedMessage, event.throwableProxy?.message).joinToString("\n")
        }
    }
}
