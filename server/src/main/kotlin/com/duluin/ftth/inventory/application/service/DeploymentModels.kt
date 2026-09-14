package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import java.util.UUID

data class DeploymentSource(val receiptId: UUID, val receiptRevision: Long, val issueRevision: Long, val issueId: UUID,
    val issueLineId: UUID, val planId: UUID, val planRevision: Long, val customerId: UUID,
    val workOrderId: UUID, val custody: PostingDimension, val assetRevision: Long,
    val serial: String, val model: String?, val createsOnu: Boolean, val provenance: AssetProvenance)
data class DeploymentPermit(val binding: DeploymentBinding, val source: DeploymentSource, val consumed: Boolean)
data class DeploymentMint(val permit: DeploymentPermit, val key: String, val hash: String)
data class DeploymentResult(val consumption: DeploymentConsumption, val key: String, val hash: String)
