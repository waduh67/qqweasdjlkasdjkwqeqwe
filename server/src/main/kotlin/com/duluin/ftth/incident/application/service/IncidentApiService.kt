package com.duluin.ftth.incident.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.incident.AffectedContact
import com.duluin.ftth.incident.IncidentApi
import com.duluin.ftth.incident.application.port.outbound.IncidentRepository
import com.duluin.ftth.incident.domain.model.IncidentRootType
import com.duluin.ftth.network.NetworkApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Implementasi kontrak lintas-module incident. Tipis dengan sengaja: menerjemahkan
 * akar masalah sebuah insiden menjadi daftar pelanggan terdampak, menyusun ulang
 * dari network + customer alih-alih menyimpannya — sama seperti panel blast radius
 * di gis, hanya berangkat dari insiden ketimbang dari ODC yang diklik di peta.
 */
@Service
@Transactional(readOnly = true)
class IncidentApiService(
    private val repository: IncidentRepository,
    private val networkApi: NetworkApi,
    private val customerApi: CustomerApi,
) : IncidentApi {

    override fun affectedContacts(incidentId: UUID): List<AffectedContact> {
        val incident = repository.findById(incidentId)
            ?: throw NotFoundException("Insiden $incidentId tidak ditemukan")

        // ONU: akarnya satu perangkat pelanggan, jadi terdampaknya persis pelanggan itu.
        if (incident.rootType == IncidentRootType.ONU) {
            val customerId = customerApi.placementsForOnus(setOf(incident.rootId))
                .firstOrNull()?.customerId ?: return emptyList()
            val customer = customerApi.findCustomer(customerId) ?: return emptyList()
            return listOf(AffectedContact(customer.id, customer.code, customer.name, customer.phone, customer.email))
        }

        // OLT/ODC/ODP: kumpulkan ODP di hilir akar, lalu ambil penghuninya.
        val odpIds = when (incident.rootType) {
            IncidentRootType.ODP -> setOf(incident.rootId)
            IncidentRootType.ODC -> networkApi.downstreamDeviceIds(emptySet(), setOf(incident.rootId)).odpIds
            IncidentRootType.OLT -> networkApi.downstreamDeviceIds(setOf(incident.rootId), emptySet()).odpIds
            // Collector adalah komponen pemantauan kami sendiri, bukan perangkat di
            // jalur layanan — matinya tidak memutus siapa pun, jadi tak ada yang disiarkan.
            IncidentRootType.COLLECTOR -> return emptyList()
            IncidentRootType.ONU -> return emptyList() // sudah ditangani di atas
        }

        // Satu pelanggan bisa muncul di lebih dari satu ODP (kasus langka) — dedup per id,
        // yang pertama menang. Terurut menurut nama agar riwayat broadcast enak dibaca.
        val occupants = odpIds.asSequence()
            .flatMap { customerApi.findOccupantsOfOdp(it).asSequence() }
            .distinctBy { it.customerId }
            .toList()

        // Penghuni ODP hanya membawa nomor telepon (bentuknya dipakai panel peta), padahal
        // siaran bisa lewat email. Emailnya diambil sekali untuk seluruh penghuni — satu
        // query tambahan, bukan satu per pelanggan.
        val emails = customerApi.findCustomersByIds(occupants.mapTo(mutableSetOf()) { it.customerId })
            .associate { it.id to it.email }

        return occupants
            .map { AffectedContact(it.customerId, it.customerCode, it.customerName, it.phone, emails[it.customerId]) }
            .sortedBy { it.name }
    }
}
