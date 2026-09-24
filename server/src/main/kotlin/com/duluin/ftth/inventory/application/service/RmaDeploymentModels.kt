package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import java.util.UUID

data class RmaDeploymentSource(val handoverId: UUID, val handoverRevision: Long, val repairCaseId: UUID, val repairRevision: Long,
    val originalAssignmentId: UUID, val originalAssignmentRevision: Long, val customerId: UUID, val workOrderId: UUID,
    val custody: PostingDimension, val assetRevision: Long, val serial: String, val model: String?,
    val createsOnu: Boolean, val provenance: AssetProvenance)
data class RmaDeploymentPermit(val binding: DeploymentBinding, val source: RmaDeploymentSource, val consumed: Boolean)
data class RmaDeploymentMint(val permit: RmaDeploymentPermit, val key: String, val hash: String)
