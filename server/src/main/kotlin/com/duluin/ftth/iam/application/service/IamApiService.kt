package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.iam.UserRef
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class IamApiService(
    private val userRepository: UserRepository,
) : IamApi {

    override fun findUser(id: UUID): UserRef? = userRepository.findById(id)?.toRef()

    override fun usersByIds(ids: Set<UUID>): List<UserRef> =
        userRepository.findAllByIds(ids).map { it.toRef() }

    private fun User.toRef() = UserRef(
        id = id,
        name = name,
        email = email.value,
        active = active,
    )
}
