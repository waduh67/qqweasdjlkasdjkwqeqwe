package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.EmergencyOverrideAudit
import com.duluin.ftth.inventory.domain.model.InventoryApprovalPolicyMatrix
import com.duluin.ftth.inventory.domain.model.InventoryApprovalType
import java.util.UUID

/**
 * Matriks persetujuan per tenant (`inventory_approval_policy` + `..._tier`, V182).
 *
 * [find] mengembalikan `null` kalau tenant belum pernah menyetel jenis tersebut — pemanggil
 * yang memutuskan memakai default bawaan. SENGAJA bukan "kembalikan default dari sini":
 * lapisan persistensi tidak boleh mengarang kebijakan, kalau tidak tidak ada cara membedakan
 * "tenant memang memilih dua tier" dari "tenant belum pernah menyentuh pengaturannya".
 */
interface InventoryApprovalPolicyRepository {
    fun find(tenantId: UUID, type: InventoryApprovalType): InventoryApprovalPolicyMatrix?

    fun findAll(tenantId: UUID): List<InventoryApprovalPolicyMatrix>

    /** Sisip atau perbarui satu jenis; tier lama DIGANTI seluruhnya, bukan digabung. */
    fun save(matrix: InventoryApprovalPolicyMatrix): InventoryApprovalPolicyMatrix
}

/**
 * Jejak override darurat (`inventory_approval_emergency_audit`, V179).
 *
 * Ditulis di transaksi yang SAMA dengan permintaannya — bukan lewat `AuditRecorder` saja.
 * `AuditRecorder` menerbitkan event yang ditulis setelah commit dan kegagalannya ditelan
 * supaya tidak menggagalkan operasi bisnis; untuk audit biasa itu benar, untuk override
 * darurat itu berarti kontrol empat-mata bisa dilangkahi TANPA satu baris jejak pun.
 */
interface InventoryApprovalAuditRepository {
    /** `true` kalau jejaknya baru ditulis, `false` kalau approval ini sudah punya jejak. */
    fun recordEmergency(entry: EmergencyOverrideAudit): Boolean

    fun emergencyOverrides(tenantId: UUID): List<EmergencyOverrideAudit>
}
