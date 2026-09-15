package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.customer.CustomerAssetOwnershipApi
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class CustomerAssetOwnershipService(private val customers: CustomerRepository, private val titles: InventoryAssetTitleApi,
    private val cutovers: InventoryTenantCutoverApi, private val authorities: CurrentAuthorityApi) : CustomerAssetOwnershipApi {
    override fun current(customerId: UUID): List<CurrentAssetOwnership> {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authorities.lockCurrent()
        if (!current.platformAdmin && "customer.onu.view" !in current.permissions)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.FORBIDDEN, "Ownership read denied"))
        val customer = customers.findById(customerId) ?: throw NotFoundException("Customer not found")
        val scope = current.areaScope
        if (scope is AuthorityScope.Restricted && customer.areaId !in scope.ids) throw NotFoundException("Customer not found")
        return titles.forCustomer(customerId)
    }
}
