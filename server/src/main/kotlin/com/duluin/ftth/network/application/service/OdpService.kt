package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.network.OdpUsageProbe
import com.duluin.ftth.network.application.port.inbound.ManageOdpUseCase
import com.duluin.ftth.network.application.port.inbound.OdpView
import com.duluin.ftth.network.application.port.inbound.SaveOdpCommand
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.domain.model.Odp
import com.duluin.ftth.network.domain.model.vo.SplitterRatio
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class OdpService(
    private val odpRepository: OdpRepository,
    private val odcRepository: OdcRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
    /** Kosong bila belum ada module lain yang menempel pada ODP. */
    private val usageProbes: List<OdpUsageProbe>,
) : ManageOdpUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, odcId: UUID?, pageRequest: PageRequest): Page<OdpView> {
        val page = odpRepository.search(query, currentUser.current().areaScope(), odcId, pageRequest)
        val odcNames = odcRepository.findAllByIds(page.content.mapNotNullTo(HashSet()) { it.odcId })
            .associate { it.id to it.name }
        return page.map { it.toView(odcNames[it.odcId]) }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): OdpView {
        val odp = requireOdp(id)
        return odp.toView(odp.odcId?.let { odcRepository.findById(it)?.name })
    }

    override fun create(command: SaveOdpCommand): OdpView {
        val code = command.code.trim().uppercase()
        if (odpRepository.existsByCode(code)) throw ConflictException("Kode ODP '$code' sudah dipakai")
        command.odcId?.let { requireOdcExists(it) }
        val odp = odpRepository.save(
            Odp.create(
                tenantId = currentUser.current().tenantId,
                code = command.code,
                name = command.name,
                address = command.address,
                location = command.location,
                areaId = command.areaId,
                odcId = command.odcId,
                splitterRatio = SplitterRatio.of(command.splitterRatio),
                capacity = command.capacity,
                status = command.status,
            ),
        )
        auditor.record(
            "odp.created", "Odp", odp.id, odp.tenantId,
            mapOf("code" to odp.code, "capacity" to odp.capacity),
        )
        return get(odp.id)
    }

    override fun update(id: UUID, command: SaveOdpCommand): OdpView {
        val odp = requireOdp(id)
        odp.update(
            name = command.name,
            address = command.address,
            location = command.location,
            areaId = command.areaId,
            splitterRatio = SplitterRatio.of(command.splitterRatio),
            capacity = command.capacity,
            status = command.status,
        )
        odpRepository.save(odp)
        auditor.record("odp.updated", "Odp", odp.id, odp.tenantId, mapOf("code" to odp.code))
        return get(id)
    }

    override fun connect(id: UUID, odcId: UUID?): OdpView {
        val odp = requireOdp(id)
        odcId?.let { requireOdcExists(it) }
        odp.connectTo(odcId)
        odpRepository.save(odp)
        auditor.record(
            "odp.connected", "Odp", odp.id, odp.tenantId,
            mapOf("code" to odp.code, "odcId" to odcId?.toString()),
        )
        return get(id)
    }

    /**
     * Menolak penghapusan selama masih ada yang menempel pada ODP ini.
     *
     * Pemeriksaannya lewat [OdpUsageProbe] karena data yang menempel (ONU
     * pelanggan) dimiliki module lain. FK-nya `ON DELETE SET NULL`, jadi tanpa
     * penjagaan ini penghapusan akan BERHASIL dan menyisakan ONU menggantung:
     * pelanggan tetap tersambung di lapangan tapi lenyap dari peta.
     */
    override fun delete(id: UUID) {
        val odp = requireOdp(id)
        usageProbes.forEach { probe ->
            val attached = probe.countAttachedTo(id)
            if (attached > 0) {
                throw ConflictException(
                    "ODP ${odp.code} masih dipakai $attached ${probe.describeUsage()}, lepas dulu sebelum dihapus",
                )
            }
        }
        odpRepository.deleteById(id)
        auditor.record("odp.deleted", "Odp", id, odp.tenantId, mapOf("code" to odp.code))
    }

    private fun requireOdp(id: UUID): Odp =
        odpRepository.findById(id) ?: throw NotFoundException("ODP $id tidak ditemukan")

    private fun requireOdcExists(odcId: UUID) {
        odcRepository.findById(odcId) ?: throw NotFoundException("ODC $odcId tidak ditemukan")
    }
}

internal fun Odp.toView(odcName: String?) = OdpView(
    id = id,
    code = code,
    name = name,
    address = address,
    location = location,
    areaId = areaId,
    odcId = odcId,
    odcName = odcName,
    splitterRatio = splitterRatio.label,
    capacity = capacity,
    status = status,
)
