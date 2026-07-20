package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.application.port.inbound.AccountQuery
import com.duluin.ftth.iam.application.port.inbound.AuthUserView
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Profil `/me`. Memuat ulang izin dari DB (bukan dari klaim token) sehingga UI
 * selalu melihat izin efektif terbaru meski token belum di-refresh.
 */
@Service
@Transactional(readOnly = true)
class AccountService(
    private val userRepository: UserRepository,
    private val assembler: AuthViewAssembler,
    private val currentUser: CurrentUserProvider,
) : AccountQuery {

    override fun currentProfile(): AuthUserView {
        val principal = currentUser.current()
        val user = userRepository.findById(principal.userId)
            ?: throw NotFoundException("Pengguna tidak ditemukan")
        return assembler.toAuthUserView(user, assembler.permissionCodesFor(user))
    }
}
