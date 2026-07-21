package com.duluin.ftth.monitoring.adapter.inbound.security

import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.domain.model.Collector
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Pencarian collector dari API key, dalam transaksinya sendiri.
 *
 * Sengaja komponen terpisah, bukan method di dalam filter: Spring menerapkan
 * `@Transactional` lewat proxy, sehingga method yang dipanggil dari dalam kelas
 * yang sama TIDAK akan pernah dibungkus transaksi — anotasinya diam-diam tak
 * berefek. Memisahkannya membuat proxy benar-benar terlibat.
 *
 * REQUIRES_NEW memastikan sesi Hibernate untuk pencarian ini ditutup sebelum
 * tenant context dipasang, sehingga koneksi tanpa tenant tidak ikut terbawa ke
 * pemrosesan request selanjutnya.
 */
@Component
class CollectorAuthenticator(
    private val collectorRepository: CollectorRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun authenticate(apiKey: String): Collector? =
        collectorRepository.findByApiKeyHash(Collector.hashApiKey(apiKey))
}
