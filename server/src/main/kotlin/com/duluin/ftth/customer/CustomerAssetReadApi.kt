package com.duluin.ftth.customer

import com.duluin.ftth.common.security.AuthorityFence
import java.util.UUID

/** Customer-owned visibility gate and current episode references, without network credentials. */
interface CustomerAssetReadApi {
    fun authorize(customerId: UUID, authority: AuthorityFence): CustomerAssetWorkspace
    fun episodes(customerId: UUID, assignments: Set<UUID>, authority: AuthorityFence): Map<UUID, CustomerAssetEpisodeState>
}
data class CustomerAssetWorkspace(val id: UUID, val code: String, val name: String, val status: String, val unresolvedDevices: Long)
data class CustomerAssetEpisodeState(val onuId: UUID, val episodeRevision: Long, val odpId: UUID?, val portNumber: Int?)
