package com.duluin.ftth.iam

import com.duluin.ftth.common.security.SessionIdentity

interface DeliveryAuthorityApi {
    fun lockActor(identity: SessionIdentity): CurrentAuthority
}
