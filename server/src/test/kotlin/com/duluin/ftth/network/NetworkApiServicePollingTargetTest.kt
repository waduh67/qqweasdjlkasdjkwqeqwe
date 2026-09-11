package com.duluin.ftth.network

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.application.port.inbound.TraceFiberPathUseCase
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.NetworkTileRenderer
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.application.port.outbound.SiteRepository
import com.duluin.ftth.network.application.port.outbound.SplitterRepository
import com.duluin.ftth.network.application.service.CableAttachmentService
import com.duluin.ftth.network.application.service.NetworkApiService
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.Olt
import com.duluin.ftth.network.domain.model.OltVendor
import com.duluin.ftth.network.domain.model.vo.ManagementIp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class NetworkApiServicePollingTargetTest {
    @Test
    fun `singular lookup exposes inactive OLT and readiness facts`() {
        val repository = mock(OltRepository::class.java)
        val olt = Olt.create(
            tenantId = UUID.randomUUID(),
            siteId = UUID.randomUUID(),
            code = "OLT-01",
            name = "OLT 01",
            vendor = OltVendor.HSGQ,
            model = null,
            managementIp = ManagementIp.of("192.0.2.10"),
            snmpCommunity = "secret",
            location = Coordinate(106.8, -6.2),
            areaId = null,
            status = AssetStatus.INACTIVE,
            snmpEnabled = false,
        )
        `when`(repository.findById(olt.id)).thenReturn(olt)
        `when`(repository.findAllByIds(setOf(olt.id))).thenReturn(listOf(olt))
        val service = service(repository)

        val target = service.findPollingTarget(olt.id)

        assertThat(target).isNotNull
        assertThat(target!!.active).isFalse()
        assertThat(target.snmpEnabled).isFalse()
        assertThat(target.vendorSupported).isTrue()
        assertThat(service.findPollingTargets(setOf(olt.id))).isEmpty()
    }

    private fun service(repository: OltRepository) = NetworkApiService(
        odpRepository = mock(OdpRepository::class.java),
        odcRepository = mock(OdcRepository::class.java),
        ponPortRepository = mock(PonPortRepository::class.java),
        oltRepository = repository,
        siteRepository = mock(SiteRepository::class.java),
        cableRepository = mock(CableRepository::class.java),
        cableAttachment = mock(CableAttachmentService::class.java),
        splitterRepository = mock(SplitterRepository::class.java),
        tileRenderer = mock(NetworkTileRenderer::class.java),
        fiberTrace = mock(TraceFiberPathUseCase::class.java),
    )
}
