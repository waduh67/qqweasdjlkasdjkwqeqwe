package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.network.application.port.inbound.OtdrTestUseCase
import com.duluin.ftth.network.application.port.inbound.OtdrTestView
import com.duluin.ftth.network.application.port.inbound.RecordOtdrTestCommand
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.OtdrTestRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableEnd
import com.duluin.ftth.network.domain.model.OtdrTest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Uji OTDR: jarak-ke-gangguan → titik perkiraan di jalur kabel.
 *
 * Jarak yang dilaporkan reflektometer adalah panjang serat, yang memuat slack di
 * tiang/closure dan karenanya lebih panjang dari jalur yang tergambar. Maka jarak
 * dikonversi lebih dulu jadi pecahan terhadap panjang optik kabel (termasuk slack),
 * baru dipetakan ke titik di geometri jalur — hasilnya perkiraan, bukan koordinat
 * pasti. Titik dihitung dari geometri terkini setiap kali dibaca, jadi selalu ikut
 * bila jalur kabel kemudian disunting.
 */
@Service
@Transactional(readOnly = true)
class OtdrTestService(
    private val tests: OtdrTestRepository,
    private val cables: CableRepository,
    private val iamApi: IamApi,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : OtdrTestUseCase {

    @Transactional
    override fun record(cableId: UUID, command: RecordOtdrTestCommand): OtdrTestView {
        val cable = requireCable(cableId)
        val test = tests.save(
            OtdrTest.record(
                tenantId = currentUser.current().tenantId,
                cableId = cable.id,
                distanceMeters = command.distanceMeters,
                measuredFrom = command.measuredFrom,
                eventType = command.eventType,
                lossDb = command.lossDb,
                note = command.note,
                recordedBy = currentUser.current().userId,
                recordedAt = command.recordedAt ?: Instant.now(),
            ),
        )
        auditor.record(
            "otdr.recorded", "Cable", cable.id, test.tenantId,
            mapOf("cableCode" to cable.code, "distanceMeters" to test.distanceMeters, "event" to test.eventType.name),
        )
        return test.toView(cable, iamApi.findUser(test.recordedBy)?.name)
    }

    override fun list(cableId: UUID): List<OtdrTestView> {
        val cable = requireCable(cableId)
        val history = tests.listByCable(cableId)
        val names = iamApi.usersByIds(history.mapTo(HashSet()) { it.recordedBy }).associate { it.id to it.name }
        return history.map { it.toView(cable, names[it.recordedBy]) }
    }

    @Transactional
    override fun delete(cableId: UUID, testId: UUID) {
        val test = tests.findById(testId)?.takeIf { it.cableId == cableId }
            ?: throw NotFoundException("Uji OTDR $testId tidak ditemukan pada kabel $cableId")
        tests.deleteById(test.id)
        auditor.record("otdr.deleted", "Cable", cableId, test.tenantId, mapOf("testId" to test.id.toString()))
    }

    private fun requireCable(id: UUID): Cable =
        cables.findById(id) ?: throw NotFoundException("Kabel $id tidak ditemukan")

    private fun OtdrTest.toView(cable: Cable, recordedByName: String?): OtdrTestView {
        val optical = cable.lengthMeters
        val beyond = distanceMeters > optical
        val point: Coordinate = if (optical <= 0) {
            cable.route.start
        } else {
            val fromStart = when (measuredFrom) {
                CableEnd.FROM -> distanceMeters / optical
                CableEnd.TO -> 1.0 - distanceMeters / optical
            }
            cable.route.pointAtFraction(fromStart)
        }
        return OtdrTestView(
            id = id,
            cableId = cableId,
            distanceMeters = distanceMeters,
            measuredFrom = measuredFrom,
            eventType = eventType,
            lossDb = lossDb,
            note = note,
            recordedBy = recordedBy,
            recordedByName = recordedByName,
            recordedAt = recordedAt,
            estimatedPoint = point,
            beyondCable = beyond,
            cableLengthMeters = optical,
        )
    }
}
