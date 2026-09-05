package com.duluin.ftth.workorder.domain.model

import com.duluin.ftth.common.domain.error.ConflictException
import java.security.MessageDigest
import java.util.UUID

enum class ProofArtifactKind { FAT, ODP, DROPCORE, ONT, ONU, OPTICAL_BEFORE, OPTICAL_AFTER, TECHNICIAN_SIGNATURE, CUSTOMER_ACKNOWLEDGEMENT, LOCATION }

data class ProofArtifactRef(
    val kind: ProofArtifactKind,
    val revisionId: UUID,
) {
    init {
        require(revisionId != UUID(0, 0)) { "Evidence revision id is required" }
    }
}

data class ProofOfWorkPacket(val revision: String, val artifacts: Set<ProofArtifactRef>) {
    init {
        require(revision.isNotBlank()) { "Proof of Work revision is required" }
        if (artifacts.map { it.kind }.distinct().size != artifacts.size) {
            throw ConflictException("Proof of Work tidak boleh memuat jenis bukti ganda")
        }
        if (artifacts.map { it.revisionId }.distinct().size != artifacts.size) {
            throw ConflictException("Satu revisi bukti tidak boleh dipakai untuk lebih dari satu jenis")
        }
    }

    fun validateFor(type: WorkOrderType): ProofOfWorkPacket {
        val actual = artifacts.map { it.kind }.toSet()
        val missing = ProofOfWorkPolicy.requiredArtifacts(type) - actual
        if (missing.isNotEmpty()) throw ConflictException("Proof of Work belum lengkap: ${missing.sortedBy { it.name }.joinToString(", ")}")
        return this
    }

    fun canonicalHash(): String {
        val canonical = buildString {
            append(revision)
            artifacts.sortedBy { it.kind.name }.forEach { append('|').append(it.kind.name).append(':').append(it.revisionId) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

object ProofArtifactCompatibility {
    fun fromEvidence(kind: EvidenceKind): ProofArtifactKind? = when (kind) {
        EvidenceKind.FAT -> ProofArtifactKind.FAT
        EvidenceKind.ODP -> ProofArtifactKind.ODP
        EvidenceKind.DROPCORE -> ProofArtifactKind.DROPCORE
        EvidenceKind.ONT -> ProofArtifactKind.ONT
        EvidenceKind.ONU -> ProofArtifactKind.ONU
        EvidenceKind.OPTICAL_BEFORE -> ProofArtifactKind.OPTICAL_BEFORE
        EvidenceKind.OPTICAL_AFTER -> ProofArtifactKind.OPTICAL_AFTER
        EvidenceKind.TECHNICIAN_SIGNATURE -> ProofArtifactKind.TECHNICIAN_SIGNATURE
        EvidenceKind.CUSTOMER_ACKNOWLEDGEMENT -> ProofArtifactKind.CUSTOMER_ACKNOWLEDGEMENT
        EvidenceKind.LOCATION -> ProofArtifactKind.LOCATION
        EvidenceKind.BEFORE, EvidenceKind.AFTER, EvidenceKind.SERIAL, EvidenceKind.OTHER -> null
    }

    val customerSignature = ProofArtifactKind.CUSTOMER_ACKNOWLEDGEMENT

    fun requireMatching(artifacts: Set<ProofArtifactRef>, revisions: Map<UUID, ProofArtifactKind>) {
        if (artifacts.any { revisions[it.revisionId] != it.kind }) {
            throw ConflictException("Revisi bukti tidak cocok dengan jenis Proof of Work")
        }
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
