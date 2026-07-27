package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.vpn.application.port.outbound.VpnNodeRef
import com.duluin.ftth.vpn.application.port.outbound.VpnNodeTokenRepository
import com.duluin.ftth.vpn.domain.model.VpnNodeToken
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Resolusi token node → hub, dalam transaksinya sendiri. Cermin persis `CollectorAuthenticator`:
 * dibuat komponen terpisah agar proxy `@Transactional` benar-benar terlibat, dan REQUIRES_NEW
 * memastikan sesi Hibernate pencarian ini (pada tabel TANPA RLS) DITUTUP rapi sebelum pembacaan
 * hub/peer berikutnya.
 */
@Component
class VpnNodeAuthenticator(
    private val nodeTokenRepository: VpnNodeTokenRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun resolve(rawToken: String): VpnNodeRef? =
        nodeTokenRepository.findRefByTokenHash(VpnNodeToken.hash(rawToken))
}
