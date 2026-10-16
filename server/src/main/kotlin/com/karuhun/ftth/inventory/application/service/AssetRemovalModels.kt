package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.inventory.*
import java.util.UUID

data class AssetRemovalRecord(val result: AssetRemovalResult, val ownership: CurrentAssetOwnership,
    val position: AssetHandoverPosition, val recoveryLocationId: UUID, val actorId: UUID, val authorizationId: UUID?,
    val signature: AssetHandoverSignature, val workOrderRevision: Long, val authorityEpoch: Long, val cutoverEpoch: Long,
    val key: String, val hash: String)
data class AssetRemovalReplay(val result: AssetRemovalResult, val actorId: UUID, val hash: String)
