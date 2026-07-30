package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.inbound.ManageNasUseCase
import com.duluin.ftth.bng.application.port.inbound.NasView
import com.duluin.ftth.bng.application.port.inbound.SaveNasCommand
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusClientRegistryPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class NasService(
    private val nasRepository: NasRepository,
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
    private val clientRegistry: RadiusClientRegistryPort,
    private val tenantApi: TenantApi,
) : ManageNasUseCase {

    @Transactional(readOnly = true)
    override fun list(): List<NasView> = nasRepository.findAll().map { it.toView() }

    @Transactional(readOnly = true)
    override fun get(id: UUID): NasView = require(id).toView()

    override fun create(command: SaveNasCommand): NasView {
        val name = command.name.trim()
        if (nasRepository.existsByName(name)) throw ConflictException("BRAS '$name' sudah ada")
        val nas = nasRepository.save(
            Nas.create(
                tenantId = currentUser.current().tenantId,
                name = command.name,
                vendor = command.vendor,
                address = command.address,
                nasIdentifier = command.nasIdentifier,
                coaSecret = command.coaSecret,
                collectorId = command.collectorId,
                apiUsername = command.apiUsername,
                apiSecret = command.apiSecret,
                apiPort = command.apiPort,
                apiUseTls = command.apiUseTls,
                apiDatabase = command.apiDatabase,
            ),
        )
        syncRadiusClient(nas)
        auditor.record("bng.nas.created", "Nas", nas.id, nas.tenantId, mapOf("name" to nas.name, "vendor" to nas.vendor.name))
        return nas.toView()
    }

    override fun update(id: UUID, command: SaveNasCommand): NasView {
        val nas = require(id)
        // Alamat lama ditangkap sebelum mutasi: bila berubah, baris klien lama dicabut.
        val previousAddress = nas.address
        val newName = command.name.trim()
        if (newName != nas.name && nasRepository.existsByName(newName)) {
            throw ConflictException("BRAS '$newName' sudah ada")
        }
        nas.update(
            name = command.name,
            vendor = command.vendor,
            address = command.address,
            nasIdentifier = command.nasIdentifier,
            coaSecret = command.coaSecret,
            collectorId = command.collectorId,
            enabled = command.enabled,
            apiUsername = command.apiUsername,
            apiSecret = command.apiSecret,
            apiPort = command.apiPort,
            apiUseTls = command.apiUseTls,
            apiDatabase = command.apiDatabase,
        )
        val saved = nasRepository.save(nas)
        syncRadiusClient(saved, previousAddress)
        auditor.record("bng.nas.updated", "Nas", saved.id, saved.tenantId, mapOf("name" to saved.name, "vendor" to saved.vendor.name))
        return saved.toView()
    }

    override fun delete(id: UUID) {
        val nas = require(id)
        val inUse = subscriberAccessRepository.countByNasId(id)
        if (inUse > 0) {
            throw ConflictException("BRAS '${nas.name}' masih menaungi $inUse akun PPPoE, pindahkan dulu")
        }
        nasRepository.deleteById(id)
        if (clientRegistry.isConfigured()) nas.address?.let { clientRegistry.deregister(nas.tenantId, it) }
        auditor.record("bng.nas.deleted", "Nas", id, nas.tenantId, mapOf("name" to nas.name))
    }

    private fun require(id: UUID): Nas =
        nasRepository.findById(id) ?: throw NotFoundException("BRAS $id tidak ditemukan")

    /**
     * Sinkronkan baris klien RADIUS (tabel `nas` platform) dengan keadaan BRAS ini —
     * inti self-service: daftar/cabut router cukup lewat aplikasi (nol sentuh
     * `clients.conf`, nol restart FreeRADIUS). Best-effort tapi tegas: bila radius-db
     * ada ([isConfigured]) namun tulisan gagal, error merambat → CRUD di-rollback,
     * sehingga state aplikasi & registri tak menyimpang. Dev/test tanpa radius-db dilewati.
     *
     * [previousAddress] = alamat sebelum sunting; bila berubah, baris lama dicabut dulu
     * (kunci baris = `nasname` = source-IP). Klien didaftarkan hanya bila BRAS enabled,
     * beralamat, dan bersecret CoA; selain itu barisnya dicabut (degradasi bersih).
     */
    private fun syncRadiusClient(nas: Nas, previousAddress: String? = null) {
        if (!clientRegistry.isConfigured()) return
        if (previousAddress != null && previousAddress != nas.address) {
            clientRegistry.deregister(nas.tenantId, previousAddress)
        }
        val nasname = nas.address
        val secret = nas.coaSecret
        if (nas.enabled && nasname != null && secret != null) {
            val shortname = tenantApi.findById(nas.tenantId)?.slug
                ?: throw ConflictException("Tenant tak punya kode (slug); klien RADIUS tak bisa didaftarkan")
            clientRegistry.register(nas.tenantId, nasname, shortname, secret)
        } else if (nasname != null) {
            clientRegistry.deregister(nas.tenantId, nasname)
        }
    }
}

private fun Nas.toView() = NasView(
    id = id,
    name = name,
    vendor = vendor.name,
    address = address,
    nasIdentifier = nasIdentifier,
    // Secret tak pernah dibocorkan; UI hanya perlu tahu sudah diisi atau belum.
    hasCoaSecret = coaSecret != null,
    collectorId = collectorId,
    enabled = enabled,
    apiUsername = apiUsername,
    hasApiSecret = apiSecret != null,
    apiPort = apiPort,
    apiUseTls = apiUseTls,
    apiDatabase = apiDatabase,
)
