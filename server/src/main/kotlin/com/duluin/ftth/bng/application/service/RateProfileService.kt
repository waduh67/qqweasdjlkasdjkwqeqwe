package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.inbound.ManageRateProfileUseCase
import com.duluin.ftth.bng.application.port.inbound.RateProfileView
import com.duluin.ftth.bng.application.port.inbound.SaveRateProfileCommand
import com.duluin.ftth.bng.application.port.outbound.RateProfileRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.RateProfile
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class RateProfileService(
    private val rateProfileRepository: RateProfileRepository,
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageRateProfileUseCase {

    @Transactional(readOnly = true)
    override fun list(): List<RateProfileView> = rateProfileRepository.findAll().map { it.toView() }

    @Transactional(readOnly = true)
    override fun get(id: UUID): RateProfileView = require(id).toView()

    override fun create(command: SaveRateProfileCommand): RateProfileView {
        val name = command.name.trim()
        if (rateProfileRepository.existsByName(name)) throw ConflictException("Paket '$name' sudah ada")
        val profile = rateProfileRepository.save(
            RateProfile.create(
                tenantId = currentUser.current().tenantId,
                name = command.name,
                description = command.description,
                downMbps = command.downMbps,
                upMbps = command.upMbps,
                radiusProfileName = command.radiusProfileName,
            ),
        )
        auditor.record("bng.plan.created", "RateProfile", profile.id, profile.tenantId, mapOf("name" to profile.name))
        return profile.toView()
    }

    override fun update(id: UUID, command: SaveRateProfileCommand): RateProfileView {
        val profile = require(id)
        val newName = command.name.trim()
        // Cek keunikan hanya bila nama benar-benar berubah, agar edit field lain
        // (kecepatan/profil RADIUS) tidak tertolak oleh namanya sendiri.
        if (newName != profile.name && rateProfileRepository.existsByName(newName)) {
            throw ConflictException("Paket '$newName' sudah ada")
        }
        profile.update(command.name, command.description, command.downMbps, command.upMbps, command.radiusProfileName)
        val saved = rateProfileRepository.save(profile)
        auditor.record("bng.plan.updated", "RateProfile", saved.id, saved.tenantId, mapOf("name" to saved.name))
        return saved.toView()
    }

    override fun delete(id: UUID) {
        val profile = require(id)
        val inUse = subscriberAccessRepository.countByRateProfileId(id)
        if (inUse > 0) {
            throw ConflictException("Paket '${profile.name}' masih dipakai $inUse akun PPPoE, pindahkan dulu")
        }
        rateProfileRepository.deleteById(id)
        auditor.record("bng.plan.deleted", "RateProfile", id, profile.tenantId, mapOf("name" to profile.name))
    }

    private fun require(id: UUID): RateProfile =
        rateProfileRepository.findById(id) ?: throw NotFoundException("Paket $id tidak ditemukan")
}

private fun RateProfile.toView() = RateProfileView(
    id = id,
    name = name,
    description = description,
    downMbps = downMbps,
    upMbps = upMbps,
    radiusProfileName = radiusProfileName,
)
