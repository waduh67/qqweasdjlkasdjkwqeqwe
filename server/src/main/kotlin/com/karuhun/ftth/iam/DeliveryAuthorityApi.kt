package com.karuhun.ftth.iam

import com.karuhun.ftth.common.security.SessionIdentity

interface DeliveryAuthorityApi {
    fun lockActor(identity: SessionIdentity): CurrentAuthority
}
