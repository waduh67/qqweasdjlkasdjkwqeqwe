package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.iam.CurrentAuthority
import com.karuhun.ftth.iam.IamApi
import com.karuhun.ftth.iam.UserRef
import com.karuhun.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import org.springframework.stereotype.Component
import java.util.UUID

/** The command and picker share current counter eligibility under the caller's authority fence. */
@Component
class WarehouseCountCounters(private val access: WarehousePolicyAccess, private val policy: WarehousePolicyPersistence,
    private val users: IamApi) {
    fun eligible(locationId: UUID, current: CurrentAuthority): List<UserRef> {
        val location = access.location(locationId, current)
        val actor = current.fence.identity.userId
        val eligible = access.directory(current).users.filter { person ->
            setOf("inventory.count.view", "inventory.count.manage").all { it in person.permissions } &&
                (person.id == actor || locationId in policy.locationsFor(person.id) && location.areaId in person.areaIds)
        }.map { it.id }.toSet()
        return users.usersByIds(eligible).filter { it.active }
    }
}
