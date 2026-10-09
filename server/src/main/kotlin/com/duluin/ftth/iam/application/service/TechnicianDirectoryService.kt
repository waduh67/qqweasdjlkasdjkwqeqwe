package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.application.port.outbound.TechnicianChoice
import com.duluin.ftth.iam.application.port.outbound.TechnicianDirectoryStore
import com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TechnicianDirectoryService(
    private val directory: TechnicianDirectoryStore,
    private val owners: TenantOwnerStore,
    private val authority: CurrentAuthorityApi,
) {
    @Transactional
    fun search(query: String?, page: PageRequest, pureOnly: Boolean): Page<TechnicianChoice> {
        val current = authority.lockCurrent()
        if (current.platformAdmin || current.permissions.none { it in operationalPermissions }) {
            throw AccessDeniedException("Tidak memiliki akses pemilih teknisi")
        }
        val term = query?.trim()?.lowercase().orEmpty()
        if (term.length > 200) throw ValidationException("Pencarian teknisi maksimal 200 karakter")
        return directory.search(term, page, pureOnly, owners.findUserId())
    }

    private val operationalPermissions = setOf("warehouse.request.review", "warehouse.technician.manage",
        "workorder.order.create", "workorder.order.update", "workorder.order.assign")
}
