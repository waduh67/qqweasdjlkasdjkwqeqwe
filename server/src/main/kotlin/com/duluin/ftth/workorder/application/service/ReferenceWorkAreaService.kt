package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.AreaRef
import com.duluin.ftth.iam.AreaReferenceApi
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ReferenceWorkAreaService(private val areas: AreaReferenceApi, private val iam: IamApi, private val authority: CurrentAuthorityApi) {
    @Transactional
    fun search(query: String?, page: PageRequest): Page<AreaRef> {
        val scope = scope()
        val term = query?.trim()?.lowercase().orEmpty()
        if (term.length > 200) throw ValidationException("Pencarian area maksimal 200 karakter")
        return areas.searchAreas(scope, term, page)
    }

    @Transactional
    fun get(id: UUID): AreaRef {
        val scope = scope()
        if (scope is AuthorityScope.Restricted && id !in scope.ids) throw NotFoundException("Area tidak ditemukan")
        return iam.areasByIds(setOf(id)).singleOrNull() ?: throw NotFoundException("Area tidak ditemukan")
    }

    private fun scope(): AuthorityScope {
        val current = authority.lockCurrent()
        if (current.platformAdmin || current.permissions.none { it in setOf("workorder.order.create", "workorder.order.update") })
            throw AccessDeniedException("Tidak memiliki akses pemilih area pekerjaan")
        return current.areaScope
    }
}
