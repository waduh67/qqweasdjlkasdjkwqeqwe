package com.duluin.ftth.workorder.domain.model

import com.duluin.ftth.common.domain.error.ConflictException
import java.security.MessageDigest
import java.util.UUID

enum class ProofArtifactKind { FAT, ODP, DROPCORE, ONT, ONU, OPTICAL_BEFORE, OPTICAL_AFTER, TECHNICIAN_SIGNATURE, CUSTOMER_ACKNOWLEDGEMENT, LOCATION }

data class ProofArtifactRef(
    val kind: ProofArtifactKind,
    val revisionId: UUID,
    val revisionState: EvidenceRevisionState,
    val correctionReason: String? = null,
) {
    init {
        require(revisionId != UUID(0, 0)) { "Evidence revision id is required" }
        if (revisionState == EvidenceRevisionState.SUPERSEDED || revisionState == EvidenceRevisionState.TOMBSTONED) {
            throw ConflictException("Evidence revision $revisionId is not current")
        }
        if (correctionReason != null && correctionReason.isBlank()) throw ConflictException("Evidence correction reason is required")
    }
}

data class ProofOfWorkPacket(val revision: Int, val artifacts: Set<ProofArtifactRef>) {
    init {
        require(revision > 0) { "Proof of Work revision must be positive" }
        require(artifacts.map { it.kind }.distinct().size == artifacts.size) { "Proof of Work cannot contain duplicate artifact kinds" }
    }

    fun validateFor(type: WorkOrderType): ProofOfWorkPacket {
        val actual = artifacts.filter { it.revisionState == EvidenceRevisionState.COMMITTED }.map { it.kind }.toSet()
        val missing = ProofOfWorkPolicy.requiredArtifacts(type) - actual
        if (missing.isNotEmpty()) throw ConflictException("Proof of Work belum lengkap: ${missing.sortedBy { it.name }.joinToString(", ")}")
        return this
    }

    fun canonicalHash(): String {
        val canonical = buildString {
            append(revision)
            artifacts.sortedBy { it.kind.name }.forEach { append('|').append(it.kind.name).append(':').append(it.revisionId).append(':').append(it.revisionState.name).append(':').append(it.correctionReason.orEmpty()) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

object ProofOfWorkPolicy {
    private val installation = setOf(ProofArtifactKind.FAT, ProofArtifactKind.ODP, ProofArtifactKind.DROPCORE, ProofArtifactKind.ONT, ProofArtifactKind.ONU, ProofArtifactKind.OPTICAL_BEFORE, ProofArtifactKind.OPTICAL_AFTER, ProofArtifactKind.TECHNICIAN_SIGNATURE, ProofArtifactKind.CUSTOMER_ACKNOWLEDGEMENT, ProofArtifactKind.LOCATION)

    fun requiredArtifacts(type: WorkOrderType): Set<ProofArtifactKind> = when (type) {
        WorkOrderType.PSB, WorkOrderType.MIGRATION -> installation
        WorkOrderType.REPAIR -> setOf(ProofArtifactKind.FAT, ProofArtifactKind.DROPCORE, ProofArtifactKind.OPTICAL_BEFORE, ProofArtifactKind.OPTICAL_AFTER, ProofArtifactKind.TECHNICIAN_SIGNATURE, ProofArtifactKind.CUSTOMER_ACKNOWLEDGEMENT, ProofArtifactKind.LOCATION)
        WorkOrderType.DISMANTLE -> setOf(ProofArtifactKind.FAT, ProofArtifactKind.ONT, ProofArtifactKind.ONU, ProofArtifactKind.TECHNICIAN_SIGNATURE, ProofArtifactKind.CUSTOMER_ACKNOWLEDGEMENT, ProofArtifactKind.LOCATION)
        WorkOrderType.PREVENTIVE -> setOf(ProofArtifactKind.FAT, ProofArtifactKind.OPTICAL_BEFORE, ProofArtifactKind.OPTICAL_AFTER, ProofArtifactKind.TECHNICIAN_SIGNATURE, ProofArtifactKind.LOCATION)
    }
}
