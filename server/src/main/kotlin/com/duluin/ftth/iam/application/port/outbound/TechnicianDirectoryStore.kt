package com.duluin.ftth.iam.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import java.util.UUID

data class TechnicianChoice(val id: UUID, val name: String, val email: String)

interface TechnicianDirectoryStore {
    fun search(query: String, page: PageRequest, pureOnly: Boolean, ownerId: UUID?): Page<TechnicianChoice>
}
